/*
    Rattlesnake Procedural
    ----------------------
    Gerador procedural de cobras para GTA San Andreas (CLEO 4 + CLEO+ 1.2+).

    Baseado no comportamento do mod original "Rattlesnake" (SnakeQa/SnakeQb,
    por ArtemQa146): o modelo base invisivel 1672 recebe 10 objetos de render
    (quadros da animacao) e a cobra passa pelos estados parado -> lingua ->
    aviso -> ataque -> volta, com audio 3D e reacoes a dano.

    O que muda aqui: as cobras nao nascem mais em coordenadas fixas. Elas sao
    procuradas proceduralmente em volta do jogador, apenas em solo natural
    (areia, grama, terra -- veja [Surfaces] no INI), com chance configuravel,
    limite de cobras simultaneas e remocao automatica (55 m por padrao).

    Estrutura de threads:
      * thread principal (gerente): le o INI, sorteia a chance, procura um
        ponto valido no raio configurado e cria o objeto base da cobra;
      * threads "SnakeWorker" (criadas via 0xE6F, filhas da principal): cada
        uma cuida de UMA cobra por vez, rodando a maquina de estados acima.
        Elas nunca terminam sozinhas -- ficam ociosas esperando trabalho --,
        o que mantem o desligamento do script limpo.

    Compilacao (gta3sc): veja tools/build.sh / tools/build.ps1.
*/

SCRIPT_START
{
// ---------------------------------------------------------------------------
// Constantes
// ---------------------------------------------------------------------------
CONST_INT OBJ_MODEL         1672        // modelo invisivel usado como corpo
CONST_INT FRAMES            10          // quadros de animacao por cobra
CONST_INT MAX_WORKERS       5           // limite de cobras simultaneas

// Variaveis compartilhadas entre as threads (0AB3/0AB4). Usamos a faixa 900+
// para nao colidir com outros mods, que costumam usar os primeiros indices.
CONST_INT SV_IDLE           900         // trabalhadores livres
CONST_INT SV_ALIVE          901         // cobras vivas
CONST_INT SV_REQUEST        902         // 1 = existe um objeto esperando
CONST_INT SV_PENDING_OBJ    903         // handle do objeto criado pelo gerente
CONST_INT SV_ENABLED        904         // copia do "Enabled" do INI
CONST_INT SV_DEBUG          905         // copia do "Debug" do INI
CONST_INT SV_MODEL_BASE     910         // 910..919 = handles dos 10 quadros

// Superficies do GTA SA (eSurfaceType). Ver README.md para a lista completa.
CONST_INT SURF_GRASS_MIN    9           // GrassShortLush
CONST_INT SURF_GRASS_MAX    20          // Meadow
CONST_INT SURF_DIRT_MIN     24          // MudWet
CONST_INT SURF_DIRT_MAX     27          // Dirttrack
CONST_INT SURF_SAND_MIN     28          // SandDeep
CONST_INT SURF_SAND_MAX     34          // ConcreteBeach
CONST_INT SURF_PSAND_MIN    74          // PSand
CONST_INT SURF_PSAND_MAX    79          // PSandbeach
CONST_INT SURF_PGRASS_MIN   80          // PGrassShort
CONST_INT SURF_PGRASS_MAX   82          // PGrassDry
CONST_INT SURF_PARKGRASS    160

// Modos de superficie ([Settings] Surfaces)
CONST_INT SURFMODE_STRICT   0           // so areia/grama/terra
CONST_INT SURFMODE_NATURAL  1           // + vegetacao, bosque, rocha, campo
CONST_INT SURFMODE_ANY      2           // qualquer solo (menos agua)

// eLevelName (retorno de GET_CITY_FROM_COORDS)
CONST_INT CITY_COUNTRYSIDE  0

// ---------------------------------------------------------------------------
// Locais do gerente
// ---------------------------------------------------------------------------
LVAR_INT   mg_enabled mg_chance mg_max mg_cities mg_surf_mode mg_debug
LVAR_INT   mg_avoid_cam mg_interval mg_workers mg_i mg_j mg_k
LVAR_INT   mg_found mg_try mg_cp mg_surf mg_obj mg_ok mg_why

LVAR_FLOAT mg_radius mg_min mg_despawn
LVAR_FLOAT mg_px mg_py mg_pz mg_ang mg_dist
LVAR_FLOAT mg_x mg_y mg_z1 mg_z2 mg_nz

// ===========================================================================
// GERENTE
// ===========================================================================

    // ---- valores padrao (o INI sobrescreve) ----
    mg_enabled   = 1
    mg_chance    = 20
    mg_interval  = 2000
    mg_max       = 3
    mg_cities    = 1
    mg_surf_mode = SURFMODE_NATURAL
    mg_avoid_cam = 1
    mg_debug     = 0
    mg_radius    = 40.0
    mg_min       = 18.0
    mg_despawn   = 55.0

    GOSUB ManagerReadIni

    // ---- exige CLEO+ 1.2.0 ou superior ----
    mg_i = 0   // versao do CLEO+ (0 = nao deu para perguntar); usada no log
    IF LOAD_DYNAMIC_LIBRARY "CLEO+.cleo" (mg_j)
        IF GET_DYNAMIC_LIBRARY_PROCEDURE "GetCleoPlusVersion" mg_j (mg_k)
            CALL_FUNCTION_RETURN mg_k 0 0 ()(mg_i)
            FREE_DYNAMIC_LIBRARY mg_j
            IF mg_i < 0x01020000
                PRINT_STRING_NOW "~r~Rattlesnake Procedural: precisa do CLEO+ 1.2.0 ou mais novo." 7000
                TERMINATE_THIS_CUSTOM_SCRIPT
            ENDIF
        ELSE
            FREE_DYNAMIC_LIBRARY mg_j
        ENDIF
    ELSE
        PRINT_STRING_NOW "~r~Rattlesnake Procedural: CLEO+ nao encontrado." 7000
        TERMINATE_THIS_CUSTOM_SCRIPT
    ENDIF

    IF mg_enabled = 0
        TERMINATE_THIS_CUSTOM_SCRIPT
    ENDIF

    // ---- carrega os 10 quadros (uma vez; as threads filhas reaproveitam) ----
    IF LOAD_SPECIAL_MODEL "ModelsQa\Snake1" "ModelsQa\Snake" (mg_obj)
        SET_CLEO_SHARED_VAR 910 mg_obj
    ELSE
        GOSUB ManagerModelError
    ENDIF
    IF LOAD_SPECIAL_MODEL "ModelsQa\Snake2" "ModelsQa\Snake" (mg_obj)
        SET_CLEO_SHARED_VAR 911 mg_obj
    ELSE
        GOSUB ManagerModelError
    ENDIF
    IF LOAD_SPECIAL_MODEL "ModelsQa\Snake3" "ModelsQa\Snake" (mg_obj)
        SET_CLEO_SHARED_VAR 912 mg_obj
    ELSE
        GOSUB ManagerModelError
    ENDIF
    IF LOAD_SPECIAL_MODEL "ModelsQa\Snake4" "ModelsQa\Snake" (mg_obj)
        SET_CLEO_SHARED_VAR 913 mg_obj
    ELSE
        GOSUB ManagerModelError
    ENDIF
    IF LOAD_SPECIAL_MODEL "ModelsQa\Snake5" "ModelsQa\Snake" (mg_obj)
        SET_CLEO_SHARED_VAR 914 mg_obj
    ELSE
        GOSUB ManagerModelError
    ENDIF
    IF LOAD_SPECIAL_MODEL "ModelsQa\Snake6" "ModelsQa\Snake" (mg_obj)
        SET_CLEO_SHARED_VAR 915 mg_obj
    ELSE
        GOSUB ManagerModelError
    ENDIF
    IF LOAD_SPECIAL_MODEL "ModelsQa\Snake7" "ModelsQa\Snake" (mg_obj)
        SET_CLEO_SHARED_VAR 916 mg_obj
    ELSE
        GOSUB ManagerModelError
    ENDIF
    IF LOAD_SPECIAL_MODEL "ModelsQa\Snake8" "ModelsQa\Snake" (mg_obj)
        SET_CLEO_SHARED_VAR 917 mg_obj
    ELSE
        GOSUB ManagerModelError
    ENDIF
    IF LOAD_SPECIAL_MODEL "ModelsQa\Snake9" "ModelsQa\Snake" (mg_obj)
        SET_CLEO_SHARED_VAR 918 mg_obj
    ELSE
        GOSUB ManagerModelError
    ENDIF
    IF LOAD_SPECIAL_MODEL "ModelsQa\Snake10" "ModelsQa\Snake" (mg_obj)
        SET_CLEO_SHARED_VAR 919 mg_obj
    ELSE
        GOSUB ManagerModelError
    ENDIF

    // ---- estado inicial das variaveis compartilhadas ----
    SET_CLEO_SHARED_VAR SV_IDLE 0
    SET_CLEO_SHARED_VAR SV_ALIVE 0
    SET_CLEO_SHARED_VAR SV_REQUEST 0
    SET_CLEO_SHARED_VAR SV_PENDING_OBJ 0
    SET_CLEO_SHARED_VAR SV_ENABLED mg_enabled
    SET_CLEO_SHARED_VAR SV_DEBUG mg_debug

    // ---- cria as threads trabalhadoras ----
    mg_workers = 0
    WHILE mg_workers < mg_max
    AND NOT mg_workers = MAX_WORKERS
        STREAM_CUSTOM_SCRIPT_FROM_LABEL SnakeWorker
        IF GET_LAST_CREATED_CUSTOM_SCRIPT (mg_obj)
            mg_workers += 1
        ELSE
            mg_workers = MAX_WORKERS
        ENDIF
    ENDWHILE

    // Se nenhuma thread nasceu, nada mais acontece e sem esta mensagem o mod
    // ficaria em silencio absoluto (era o pior caso para diagnostico).
    IF mg_workers = 0
        PRINT_STRING_NOW "~r~Rattlesnake Procedural: falha ao criar as threads (CLEO+ 1.2.0+?)." 7000
        TERMINATE_THIS_CUSTOM_SCRIPT
    ENDIF

    // Aviso de que o script esta vivo: apareceu na tela = gerente rodando.
    IF mg_debug = 1
        // FRAMES vai por variavel: como CONST_INT o gta3sc escreveria o nome
        // da constante como texto (ver o comentario do log).
        mg_obj = FRAMES
        PRINT_FORMATTED_NOW "Rattlesnake Procedural: %i quadros, %i threads, chance %i%%" 6000 mg_obj mg_workers mg_chance
    ENDIF

    // ---- log em arquivo ----
    // cleo\Rattlesnake_procedural.log e reescrito a cada partida: se o arquivo
    // nao existir (ou estiver vazio) depois de abrir o jogo, o script nem
    // chegou aqui. O "%c" recebendo 10 no fim de cada linha e o \n, porque o
    // gta3script nao interpreta escapes dentro de string.
    OPEN_FILE "cleo\Rattlesnake_procedural.log" "w" (mg_j)
    IF mg_j > 0
        WRITE_FORMATTED_STRING_TO_FILE mg_j "=== Rattlesnake Procedural v2 ===%c" 10
        // FRAMES nao pode ir direto como vararg: com CONST_INT o gta3sc
        // escreve o NOME da constante como texto (bug do compilador), o que
        // desalinha todos os %i da linha. Passando por uma variavel o valor
        // sai certo. mg_i tem a versao do CLEO+ e mg_k esta livre aqui.
        mg_k = FRAMES
        WRITE_FORMATTED_STRING_TO_FILE mg_j "CLEO+ 0x%x | quadros %i | threads %i | debug %i%c" mg_i mg_k mg_workers mg_debug 10
        WRITE_FORMATTED_STRING_TO_FILE mg_j "chance %i%% | max %i | raio %.1f | min %.1f | despawn %.1f%c" mg_chance mg_max mg_radius mg_min mg_despawn 10
        WRITE_FORMATTED_STRING_TO_FILE mg_j "surfaces %i | cidades %i | evita camera %i%c" mg_surf_mode mg_cities mg_avoid_cam 10
        CLOSE_FILE mg_j
    ENDIF

    // ---- laco principal ----
    // Sem "WHILE TRUE": no gta3script o TRUE vira o opcode 0x485
    // (IS_PC_VERSION), que nao e um valor booleano. O laco com rotulo e GOTO
    // faz o mesmo e e o mesmo padrao usado no WorkerLoop.
ManagerLoop:
    WAIT mg_interval
    GOSUB ManagerReadIni
    SET_CLEO_SHARED_VAR SV_ENABLED mg_enabled
    SET_CLEO_SHARED_VAR SV_DEBUG mg_debug

    IF mg_enabled = 1
        // o INI pode pedir mais cobras do que as threads criadas no inicio
        WHILE mg_workers < mg_max
        AND NOT mg_workers = MAX_WORKERS
            STREAM_CUSTOM_SCRIPT_FROM_LABEL SnakeWorker
            IF GET_LAST_CREATED_CUSTOM_SCRIPT (mg_obj)
                mg_workers += 1
            ELSE
                mg_workers = MAX_WORKERS
            ENDIF
        ENDWHILE

        GET_CLEO_SHARED_VAR SV_REQUEST (mg_k)
        IF mg_k = 0
            GET_CLEO_SHARED_VAR SV_ALIVE (mg_i)
            GET_CLEO_SHARED_VAR SV_IDLE (mg_j)
            IF mg_i < mg_max
            AND mg_j > 0
                IF RANDOM_PERCENT mg_chance
                    GOSUB ManagerFindSpot
                    IF mg_found = 1
                        CREATE_OBJECT_NO_SAVE OBJ_MODEL mg_x mg_y mg_z1 FALSE TRUE (mg_obj)
                        IF DOES_OBJECT_EXIST mg_obj
                            // o modelo 1672 nao deve aparecer: quem desenha a
                            // cobra sao os render objects (0xF04)
                            SET_OBJECT_SCALE mg_obj 0.0
                            SET_CLEO_SHARED_VAR SV_PENDING_OBJ mg_obj
                            SET_CLEO_SHARED_VAR SV_REQUEST 1
                            // registro permanente (mesmo com Debug = 0): prova
                            // que o mod chegou a criar uma cobra. mg_k guardou a
                            // leitura de SV_REQUEST e e relido no proximo ciclo.
                            OPEN_FILE "cleo\Rattlesnake_procedural.log" "a" (mg_k)
                            IF mg_k > 0
                                WRITE_FORMATTED_STRING_TO_FILE mg_k "cobra criada em %.1f %.1f %.1f (surf %i)%c" mg_x mg_y mg_z1 mg_surf 10
                                CLOSE_FILE mg_k
                            ENDIF
                            IF mg_debug = 1
                                PRINT_FORMATTED_NOW "Rattlesnake: cobra em %.1f %.1f %.1f" 2500 mg_x mg_y mg_z1
                            ENDIF
                        ENDIF
                    ELSE
                        IF mg_debug = 1
                            PRINT_FORMATTED_NOW "Rattlesnake: nenhum ponto valido (%i tentativas)" 2500 mg_try
                        ENDIF
                    ENDIF
                ENDIF
            ENDIF
        ENDIF
    ENDIF
    GOTO ManagerLoop

// ---------------------------------------------------------------------------
// Gerente: le o INI
// ---------------------------------------------------------------------------
ManagerReadIni:
    // O opcode de INI escreve 0x80000000 numa variavel INT quando o arquivo ou
    // a chave nao existem (com FLOAT ele nao escreve nada, por isso os padroes
    // de float ficam logo antes das leituras). Cada leitura de INT tem,
    // portanto, o seu valor padrao no IF NOT.
    IF NOT READ_INT_FROM_INI_FILE "cleo\SnakeProcedural.ini" "Settings" "Enabled" (mg_enabled)
        mg_enabled = 1
    ENDIF
    IF NOT READ_INT_FROM_INI_FILE "cleo\SnakeProcedural.ini" "Settings" "Chance" (mg_chance)
        mg_chance = 20
    ENDIF
    IF NOT READ_INT_FROM_INI_FILE "cleo\SnakeProcedural.ini" "Settings" "CheckInterval" (mg_interval)
        mg_interval = 2000
    ENDIF
    IF NOT READ_INT_FROM_INI_FILE "cleo\SnakeProcedural.ini" "Settings" "MaxSnakes" (mg_max)
        mg_max = 3
    ENDIF
    IF NOT READ_INT_FROM_INI_FILE "cleo\SnakeProcedural.ini" "Settings" "InCities" (mg_cities)
        mg_cities = 1
    ENDIF
    IF NOT READ_INT_FROM_INI_FILE "cleo\SnakeProcedural.ini" "Settings" "Surfaces" (mg_surf_mode)
        mg_surf_mode = SURFMODE_NATURAL
    ENDIF
    IF NOT READ_INT_FROM_INI_FILE "cleo\SnakeProcedural.ini" "Settings" "AvoidCameraView" (mg_avoid_cam)
        mg_avoid_cam = 1
    ENDIF
    IF NOT READ_INT_FROM_INI_FILE "cleo\SnakeProcedural.ini" "Settings" "Debug" (mg_debug)
        mg_debug = 0
    ENDIF
    READ_FLOAT_FROM_INI_FILE "cleo\SnakeProcedural.ini" "Settings" "SpawnRadius" (mg_radius)
    READ_FLOAT_FROM_INI_FILE "cleo\SnakeProcedural.ini" "Settings" "MinDistance" (mg_min)
    READ_FLOAT_FROM_INI_FILE "cleo\SnakeProcedural.ini" "Settings" "DespawnDistance" (mg_despawn)

    IF mg_chance < 1
        mg_chance = 1
    ENDIF
    IF mg_chance > 100
        mg_chance = 100
    ENDIF
    IF mg_interval < 500
        mg_interval = 500
    ENDIF
    IF mg_interval > 60000
        mg_interval = 60000
    ENDIF
    IF mg_max < 1
        mg_max = 1
    ENDIF
    IF mg_max > MAX_WORKERS
        mg_max = MAX_WORKERS
    ENDIF
    IF mg_surf_mode < SURFMODE_STRICT
        mg_surf_mode = SURFMODE_STRICT
    ENDIF
    IF mg_surf_mode > SURFMODE_ANY
        mg_surf_mode = SURFMODE_ANY
    ENDIF
    IF mg_radius > 40.0
        mg_radius = 40.0
    ENDIF
    IF mg_radius < 5.0
        mg_radius = 5.0
    ENDIF
    IF mg_min < 5.0
        mg_min = 5.0
    ENDIF
    IF mg_min > mg_radius
        mg_min = mg_radius
    ENDIF
    IF mg_despawn < mg_radius
        mg_despawn = mg_radius
    ENDIF
    IF mg_despawn > 55.0
        mg_despawn = 55.0
    ENDIF
    RETURN

// ---------------------------------------------------------------------------
// Gerente: procura um ponto valido em volta do jogador
// Saida: mg_found (1 = mg_x/mg_y/mg_z1 valem), mg_try (numero de tentativas)
//
// mg_why guarda o motivo da recusa de cada tentativa para a mensagem de Debug:
//   0 = passou          1 = dentro do campo de visao da camera
//   2 = raio sem chao   3 = nao leu a superficie do colpoint
//   4 = superficie nao permitida                5 = terreno muito inclinado
//   6 = em cidade com InCities = 0              9 = jogador fora de jogo
// ---------------------------------------------------------------------------
ManagerFindSpot:
    mg_found = 0
    mg_try = 0
    WHILE mg_try < 8
    AND mg_found = 0
        mg_try += 1
        mg_ok = 1
        mg_why = 0
        mg_surf = 0   // o log nunca mostra o material de outra tentativa

        GET_PLAYER_CHAR 0 (mg_j)
        IF NOT IS_PLAYER_PLAYING 0
            mg_ok = 0
            mg_why = 9
        ENDIF

        IF mg_ok = 1
            GET_CHAR_COORDINATES mg_j (mg_px mg_py mg_pz)

            // ponto aleatorio no anel [MinDistance, SpawnRadius]
            GENERATE_RANDOM_FLOAT_IN_RANGE 0.0 360.0 (mg_ang)
            GENERATE_RANDOM_FLOAT_IN_RANGE mg_min mg_radius (mg_dist)
            GET_COORD_FROM_ANGLED_DISTANCE mg_px mg_py mg_ang mg_dist (mg_x mg_y)

            // raio vertical do chao: 1,5 m acima dos pes ate 3 m abaixo. Fica
            // aqui em cima porque depende de mg_pz, que a checagem de camera
            // logo abaixo reaproveita como variavel temporaria.
            mg_z1 = mg_pz
            mg_z1 += 1.5
            mg_z2 = mg_pz
            mg_z2 -= 3.0

            // ---- regra extra: nao aparecer dentro do campo de visao ----
            // 0xF0E devolve o ponto do mundo que a camera em 3a pessoa esta
            // mirando; o angulo entre "jogador -> alvo da camera" e
            // "jogador -> ponto sorteado" diz se a cobra nasceria na tela.
            // (antes isso era feito lendo a rotacao da camera, cuja convencao
            //  de eixos nao esta documentada em lugar nenhum)
            // Variaveis reaproveitadas como temporarias aqui: mg_nz e mg_ang
            // (as coordenadas do alvo), mg_pz (o angulo do eixo), mg_ang de
            // novo (o angulo do ponto) e mg_dist (lixo). Todas sao reescritas
            // antes de serem lidas de novo, e o z do jogador ja virou mg_z1 e
            // mg_z2 no raio acima.
            IF mg_avoid_cam = 1
                // saidas: 1a a camera, 2a o alvo. So o alvo importa, entao as
                // demais vao para mg_dist (morto aqui: o sorteio do anel ja usou).
                GET_THIRD_PERSON_CAMERA_TARGET 60.0 mg_px mg_py mg_pz (mg_dist mg_dist mg_dist mg_nz mg_ang mg_dist)
                GET_ANGLE_FROM_TWO_COORDS mg_px mg_py mg_nz mg_ang (mg_pz)  // eixo: jogador -> alvo
                GET_ANGLE_FROM_TWO_COORDS mg_px mg_py mg_x mg_y (mg_ang)    // jogador -> ponto
                mg_ang -= mg_pz
                IF mg_ang > 180.0
                    mg_ang -= 360.0
                ENDIF
                IF mg_ang < -180.0
                    mg_ang += 360.0
                ENDIF
                IF mg_ang > -55.0
                AND NOT mg_ang > 55.0
                    mg_ok = 0
                    mg_why = 1
                    mg_j = 0   // nada foi lancado: 'ent 0' na linha do log
                ENDIF
            ENDIF
        ENDIF

        // ---- procura o chao ----
        IF mg_ok = 1
            GET_LABEL_POINTER ColPointBuffer (mg_cp)
            // ultimo parametro = entidade a ignorar: 0 (nenhuma). Nao usar -1,
            // o CLEO+ repassa o valor direto para CWorld::pIgnoreEntity, que e
            // um ponteiro de verdade, e 0xFFFFFFFF nao existe.
            IF GET_COLLISION_BETWEEN_POINTS (mg_x mg_y mg_z1) (mg_x mg_y mg_z2) TRUE FALSE FALSE FALSE FALSE TRUE TRUE TRUE 0 mg_cp (mg_x mg_y mg_z1 mg_j)
                // 0xD3C e 0xD3B NAO sao condicoes no CLEO+ (0xD3A e). Usar
                // uma delas dentro de IF deixa o resultado do IF indefinido -
                // era isso que recusava TODAS as tentativas com motivo 3, em
                // qualquer lugar do mapa, e nenhuma cobra nascia. Aqui elas
                // sao chamadas soltas (como no script de teste do CLEO+) e o
                // valor lido e testado logo depois.
                GET_COLPOINT_SURFACE mg_cp (mg_surf)
                GET_COLPOINT_NORMAL_VECTOR mg_cp (mg_nz mg_nz mg_nz)
                // superficie valida vai de 1 a 178; 0 significa que o
                // colpoint nao foi preenchido (ponteiro recusado pelo CLEO+).
                IF NOT mg_surf > 0
                    mg_ok = 0
                    mg_why = 3
                ENDIF
                IF mg_surf > 178
                    mg_ok = 0
                    mg_why = 3
                ENDIF
                IF mg_ok = 1
                    GOSUB ManagerSurfaceAllowed
                    IF mg_ok = 1
                    AND NOT mg_nz > 0.75
                        mg_ok = 0      // terreno muito inclinado
                        mg_why = 5
                    ENDIF
                    IF mg_ok = 1
                    AND mg_cities = 0
                        // mg_k esta livre aqui (o gerente so reusa ele
                        // depois desta sub, para abrir o log da cobra)
                        // e mg_surf nao pode ser sobrescrito: ele aparece
                        // no log da cobra criada.
                        GET_CITY_FROM_COORDS mg_x mg_y mg_z1 (mg_k)
                        IF NOT mg_k = CITY_COUNTRYSIDE
                            mg_ok = 0  // dentro de cidade (LS/SF/LV)
                            mg_why = 6
                        ENDIF
                    ENDIF
                    IF mg_ok = 1
                        mg_found = 1
                    ENDIF
                ENDIF
            ELSE
                mg_ok = 0
                mg_why = 2
            ENDIF
        ENDIF

        // A impressao fica fora de todos os blocos de decisao, senao a recusa
        // pela camera (que acontece antes do raio) nao aparecia em lugar nenhum.
        IF mg_debug = 1
            PRINT_FORMATTED_NOW "Rattlesnake: tentativa %i surf %i ok %i motivo %i" 2000 mg_try mg_surf mg_ok mg_why
            // A mesma linha vai para o log. mg_obj esta livre aqui: o objeto da
            // cobra so e criado depois que esta sub retorna.
            OPEN_FILE "cleo\Rattlesnake_procedural.log" "a" (mg_obj)
            IF mg_obj > 0
                WRITE_FORMATTED_STRING_TO_FILE mg_obj "tentativa %i surf %i ok %i motivo %i%c" mg_try mg_surf mg_ok mg_why 10
                // Linha extra de diagnostico (so com Debug = 1): mostra
                // o raio, o ponteiro do colpoint, a normal e a entidade
                // atingida. cp com valor baixo (ou 00000000) = o CLEO+
                // recusou o ponteiro e nada foi escrito no buffer.
                WRITE_FORMATTED_STRING_TO_FILE mg_obj "  ponto %.1f %.1f | z %.1f ate %.1f | cp %p | nz %.2f | ent %p%c" mg_x mg_y mg_z1 mg_z2 mg_cp mg_nz mg_j 10
                CLOSE_FILE mg_obj
            ENDIF
        ENDIF
    ENDWHILE
    RETURN

// ---------------------------------------------------------------------------
// Gerente: a superficie detectada pode receber uma cobra?
// Entrada: mg_surf, mg_surf_mode.  Saida: mg_ok = 0 quando nao pode.
// ---------------------------------------------------------------------------
ManagerSurfaceAllowed:
    // ---- areia (praia, deserto) ----
    IF mg_surf >= SURF_SAND_MIN
    AND NOT mg_surf > SURF_SAND_MAX
        RETURN
    ENDIF
    IF mg_surf >= SURF_PSAND_MIN
    AND NOT mg_surf > SURF_PSAND_MAX
        RETURN
    ENDIF
    // ---- grama / campo de golfe / canteiro / prado ----
    IF mg_surf >= SURF_GRASS_MIN
    AND NOT mg_surf > SURF_GRASS_MAX
        RETURN
    ENDIF
    IF mg_surf >= SURF_PGRASS_MIN
    AND NOT mg_surf > SURF_PGRASS_MAX
        RETURN
    ENDIF
    IF mg_surf = SURF_PARKGRASS
        RETURN
    ENDIF
    // ---- terra, lama e pista de terra ----
    IF mg_surf >= SURF_DIRT_MIN
    AND NOT mg_surf > SURF_DIRT_MAX
        RETURN
    ENDIF
    IF mg_surf = 123
        RETURN   // PDirtrocky
    ENDIF
    IF mg_surf = 124
        RETURN   // PDirtweeds
    ENDIF
    IF mg_surf = 153
        RETURN   // PGrassdirtmix
    ENDIF

    IF mg_surf_mode >= SURFMODE_NATURAL
        // ---- vegetacao, bosque, rocha, campos e mato ----
        IF mg_surf = 21
            RETURN   // Wasteground
        ENDIF
        IF mg_surf = 22
            RETURN   // Woodlandground
        ENDIF
        IF mg_surf = 23
            RETURN   // Vegetation
        ENDIF
        IF mg_surf >= 35
        AND NOT mg_surf > 37
            RETURN   // RockDry/RockWet/RockCliff
        ENDIF
        IF mg_surf = 40
            RETURN   // Cornfield
        ENDIF
        IF mg_surf = 41
            RETURN   // Hedge
        ENDIF
        IF mg_surf = 83
            RETURN   // PWoodland
        ENDIF
        IF mg_surf = 84
            RETURN   // PWooddense
        ENDIF
        IF mg_surf = 109
            RETURN   // PMountain
        ENDIF
        IF mg_surf = 110
            RETURN   // PMarsh
        ENDIF
        IF mg_surf >= 111
        AND NOT mg_surf > 114
            RETURN   // PBushy..
        ENDIF
        IF mg_surf >= 115
        AND NOT mg_surf > 122
            RETURN   // PGrass*
        ENDIF
        IF mg_surf = 125
            RETURN   // PGrassweeds
        ENDIF
        IF mg_surf = 126
            RETURN   // PRiveredge
        ENDIF
        IF mg_surf >= 128
        AND NOT mg_surf > 133
            RETURN   // foresta / deserto
        ENDIF
        IF mg_surf = 143
            RETURN   // PCactusdense
        ENDIF
        IF mg_surf = 145
            RETURN   // PCornfield
        ENDIF
        IF mg_surf >= 146
        AND NOT mg_surf > 153
            RETURN   // PGrass*
        ENDIF
    ENDIF

    IF mg_surf_mode = SURFMODE_ANY
        // qualquer solo solido: barra apenas agua e superficies que nao sao chao
        IF mg_surf = 38
            mg_ok = 0
            RETURN
        ENDIF
        IF mg_surf = 39
            mg_ok = 0
            RETURN
        ENDIF
        IF mg_surf >= 96
        AND NOT mg_surf > 100
            mg_ok = 0
            RETURN
        ENDIF
        IF mg_surf >= 154
        AND NOT mg_surf > 157
            mg_ok = 0
            RETURN
        ENDIF
        IF mg_surf = 62
        OR mg_surf = 63
        OR mg_surf = 177
            mg_ok = 0
            RETURN
        ENDIF
        RETURN
    ENDIF

    // chegou aqui: superficie nao permitida
    mg_ok = 0
    mg_why = 4
    RETURN

// ---------------------------------------------------------------------------
// Gerente: falha ao carregar os modelos
// ---------------------------------------------------------------------------
ManagerModelError:
    PRINT_STRING_NOW "~r~Rattlesnake Procedural: falha ao carregar ModelsQa\Snake*.dff" 7000
    TERMINATE_THIS_CUSTOM_SCRIPT
    RETURN
}
SCRIPT_END

ColPointBuffer:
DUMP
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 //32
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 //64
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 //96
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 //128
ENDDUMP

// ===========================================================================
// TRABALHADOR -- uma cobra por vez
// ===========================================================================
{
LVAR_INT   wk_obj
LVAR_INT   wk_rend[10]
LVAR_INT   wk_prog
LVAR_INT   wk_event
LVAR_INT   wk_snd_idle
LVAR_INT   wk_snd_atk
LVAR_INT   wk_snd_die
LVAR_INT   wk_i wk_j wk_k wk_debug wk_log
LVAR_INT   wk_alive
LVAR_FLOAT wk_vol
LVAR_FLOAT wk_despawn
LVAR_FLOAT wk_x wk_y wk_z
LVAR_FLOAT wk_x2 wk_y2 wk_z2
LVAR_FLOAT wk_ang

SnakeWorker:
    // anuncia que esta livre
    GET_CLEO_SHARED_VAR SV_IDLE (wk_i)
    wk_i += 1
    SET_CLEO_SHARED_VAR SV_IDLE wk_i

WorkerLoop:
    WAIT 250
    GET_CLEO_SHARED_VAR SV_REQUEST (wk_i)
    IF wk_i = 1
        GET_CLEO_SHARED_VAR SV_PENDING_OBJ (wk_obj)
        SET_CLEO_SHARED_VAR SV_REQUEST 0
        IF DOES_OBJECT_EXIST wk_obj
            GOSUB WorkerTakeJob
            GOSUB WorkerSnake
            GOSUB WorkerCleanup
            GOSUB WorkerRelease
        ELSE
            // Sem este aviso o pedido sumia calado: o gerente dizia "cobra em
            // x y z" e nada aparecia, sem nenhuma pista do porque.
            GET_CLEO_SHARED_VAR SV_DEBUG (wk_debug)
            IF wk_debug = 1
                PRINT_STRING_NOW "~r~Rattlesnake: objeto da cobra nao existe (pedido descartado)." 4000
            ENDIF
            OPEN_FILE "cleo\Rattlesnake_procedural.log" "a" (wk_log)
            IF wk_log > 0
                WRITE_FORMATTED_STRING_TO_FILE wk_log "pedido descartado: objeto %i nao existe%c" wk_obj 10
                CLOSE_FILE wk_log
            ENDIF
        ENDIF
    ENDIF
    GOTO WorkerLoop

// ---------------------------------------------------------------------------
WorkerTakeJob:
    GET_CLEO_SHARED_VAR SV_IDLE (wk_i)
    wk_i -= 1
    SET_CLEO_SHARED_VAR SV_IDLE wk_i
    GET_CLEO_SHARED_VAR SV_ALIVE (wk_i)
    wk_i += 1
    SET_CLEO_SHARED_VAR SV_ALIVE wk_i
    RETURN

// ---------------------------------------------------------------------------
WorkerRelease:
    GET_CLEO_SHARED_VAR SV_ALIVE (wk_i)
    wk_i -= 1
    IF wk_i < 0
        wk_i = 0
    ENDIF
    SET_CLEO_SHARED_VAR SV_ALIVE wk_i
    GET_CLEO_SHARED_VAR SV_IDLE (wk_i)
    wk_i += 1
    SET_CLEO_SHARED_VAR SV_IDLE wk_i
    RETURN

// ---------------------------------------------------------------------------
// Monta a cobra e roda a maquina de estados ate ela sumir ou morrer.
// ---------------------------------------------------------------------------
WorkerSnake:
    // ---- parametros do INI ----
    wk_vol = 0.6
    READ_FLOAT_FROM_INI_FILE "cleo\SnakeProcedural.ini" "Settings" "Volume" (wk_vol)
    IF wk_vol < 0.0
        wk_vol = 0.0
    ENDIF
    IF wk_vol > 1.0
        wk_vol = 1.0
    ENDIF
    wk_despawn = 55.0
    READ_FLOAT_FROM_INI_FILE "cleo\SnakeProcedural.ini" "Settings" "DespawnDistance" (wk_despawn)

    // ---- quadros de animacao (render objects sobre o objeto invisivel) ----
    wk_alive = 1
    wk_i = 0
    WHILE wk_i < FRAMES
        wk_j = SV_MODEL_BASE
        wk_j += wk_i
        GET_CLEO_SHARED_VAR wk_j (wk_k)
        CREATE_RENDER_OBJECT_TO_OBJECT_FROM_SPECIAL wk_obj wk_k 0.0 0.0 0.0 0.0 0.0 0.0 (wk_rend[wk_i])
        IF wk_rend[wk_i] = 0
            wk_alive = 0
        ENDIF
        wk_i += 1
    ENDWHILE
    IF wk_alive = 0
        RETURN
    ENDIF

    SET_OBJECT_SCALE wk_obj 0.0
    GENERATE_RANDOM_FLOAT_IN_RANGE 0.0 360.0 (wk_ang)
    SET_OBJECT_HEADING wk_obj wk_ang

    TIMERA = 0
    TIMERB = 0
    wk_prog = 0
    wk_event = 1

    wk_snd_idle = 0
    wk_snd_atk = 0
    wk_snd_die = 0
    IF LOAD_3D_AUDIO_STREAM "SoundsQa/SnakeAttack.mp3" (wk_snd_atk)
        SET_PLAY_3D_AUDIO_STREAM_AT_OBJECT wk_snd_atk wk_obj
    ENDIF
    IF LOAD_3D_AUDIO_STREAM "SoundsQa/SnakeIdle.mp3" (wk_snd_idle)
        SET_PLAY_3D_AUDIO_STREAM_AT_OBJECT wk_snd_idle wk_obj
    ENDIF

    // ---- laco por quadro ----
    WHILE wk_alive = 1
        WAIT 0

        // desligou o mod ou o jogador sumiu?
        GET_CLEO_SHARED_VAR SV_ENABLED (wk_i)
        IF NOT wk_i = 1
            BREAK
        ENDIF
        IF NOT IS_PLAYER_PLAYING 0
            BREAK
        ENDIF
        IF NOT DOES_OBJECT_EXIST wk_obj
            BREAK
        ENDIF

        GET_PLAYER_CHAR 0 (wk_i)

        // longe demais: remove
        IF NOT LOCATE_CHAR_DISTANCE_TO_OBJECT wk_i wk_obj wk_despawn
            BREAK
        ENDIF

        // ---- transicoes ----
        IF NOT wk_prog >= 146
        AND LOCATE_CHAR_DISTANCE_TO_OBJECT wk_i wk_obj 5.0
            wk_prog = 146
            SET_AUDIO_STREAM_VOLUME wk_snd_idle wk_vol
            SET_AUDIO_STREAM_STATE wk_snd_idle 1
            TIMERB = 0
            wk_event = 0
        ELSE
            IF wk_prog >= 146
            AND NOT wk_prog >= 246
            AND NOT LOCATE_CHAR_DISTANCE_TO_OBJECT wk_i wk_obj 7.0
                wk_prog = 346
                TIMERA = 0
            ENDIF
        ENDIF

        IF TIMERB > 1000
        AND NOT wk_prog >= 246
        AND LOCATE_CHAR_DISTANCE_TO_OBJECT wk_i wk_obj 2.0
        AND NOT IS_CHAR_IN_ANY_CAR wk_i
            wk_prog = 246
            SET_AUDIO_STREAM_VOLUME wk_snd_atk wk_vol
            SET_AUDIO_STREAM_STATE wk_snd_atk 1
            TIMERB = -1000
            wk_event = 0
        ENDIF

        // ---- animacao parado / lingua ----
        IF NOT wk_prog >= 146
        AND wk_event = 1
            IF TIMERB > 5000
                TIMERB = 0
            ENDIF
            IF TIMERB > 1000
                IF wk_prog = 0
                    SET_RENDER_OBJECT_VISIBLE wk_rend[1] FALSE
                    SET_RENDER_OBJECT_VISIBLE wk_rend[2] FALSE
                    SET_RENDER_OBJECT_VISIBLE wk_rend[0] TRUE
                ENDIF
            ELSE
                IF wk_prog = 0
                    wk_prog = 1
                ENDIF
            ENDIF
            IF wk_prog > 0
            AND TIMERA > 60
                IF TIMERB < 1000
                AND wk_prog = 1
                    SET_RENDER_OBJECT_VISIBLE wk_rend[0] FALSE
                    SET_RENDER_OBJECT_VISIBLE wk_rend[2] FALSE
                    SET_RENDER_OBJECT_VISIBLE wk_rend[1] TRUE
                    wk_prog = 2
                    TIMERA = 0
                ELSE
                    IF wk_prog = 2
                    AND TIMERB < 1000
                        SET_RENDER_OBJECT_VISIBLE wk_rend[1] FALSE
                        SET_RENDER_OBJECT_VISIBLE wk_rend[2] TRUE
                        wk_prog = 1
                        TIMERA = 0
                    ELSE
                        IF TIMERB > 1000
                            wk_prog = 0
                        ENDIF
                    ENDIF
                ENDIF
            ENDIF
        ENDIF

        // ---- animacao de aviso ----
        IF wk_prog >= 146
        AND NOT wk_prog >= 246
        AND wk_event = 0
            GOSUB WorkerFacePlayer
            IF TIMERA > 50
                IF wk_prog = 146
                    SET_RENDER_OBJECT_VISIBLE wk_rend[0] FALSE
                    SET_RENDER_OBJECT_VISIBLE wk_rend[1] FALSE
                    SET_RENDER_OBJECT_VISIBLE wk_rend[2] FALSE
                    SET_RENDER_OBJECT_VISIBLE wk_rend[8] FALSE
                    SET_RENDER_OBJECT_VISIBLE wk_rend[3] TRUE
                    wk_prog = 147
                    TIMERA = 0
                ELSE
                    IF wk_prog = 147
                        SET_RENDER_OBJECT_VISIBLE wk_rend[3] FALSE
                        SET_RENDER_OBJECT_VISIBLE wk_rend[5] FALSE
                        SET_RENDER_OBJECT_VISIBLE wk_rend[6] FALSE
                        SET_RENDER_OBJECT_VISIBLE wk_rend[4] TRUE
                        wk_prog = 148
                        TIMERA = 0
                    ELSE
                        IF TIMERB < 1500
                            IF wk_prog = 148
                                SET_RENDER_OBJECT_VISIBLE wk_rend[4] FALSE
                                SET_RENDER_OBJECT_VISIBLE wk_rend[6] FALSE
                                SET_RENDER_OBJECT_VISIBLE wk_rend[5] TRUE
                                wk_prog = 149
                                TIMERA = 0
                            ELSE
                                SET_RENDER_OBJECT_VISIBLE wk_rend[5] FALSE
                                SET_RENDER_OBJECT_VISIBLE wk_rend[6] TRUE
                                wk_prog = 148
                                TIMERA = 0
                            ENDIF
                        ELSE
                            wk_prog = 147
                            IF TIMERB > 5000
                                TIMERB = 0
                                SET_AUDIO_STREAM_VOLUME wk_snd_idle wk_vol
                                SET_AUDIO_STREAM_STATE wk_snd_idle 1
                            ENDIF
                        ENDIF
                    ENDIF
                ENDIF
            ENDIF
        ENDIF

        // ---- animacao de ataque ----
        IF wk_prog >= 246
        AND NOT wk_prog >= 346
        AND wk_event = 0
            GOSUB WorkerFacePlayer
            IF TIMERA > 40
                IF wk_prog = 246
                    SET_RENDER_OBJECT_VISIBLE wk_rend[3] FALSE
                    SET_RENDER_OBJECT_VISIBLE wk_rend[4] FALSE
                    SET_RENDER_OBJECT_VISIBLE wk_rend[5] FALSE
                    SET_RENDER_OBJECT_VISIBLE wk_rend[6] FALSE
                    SET_RENDER_OBJECT_VISIBLE wk_rend[7] TRUE
                    wk_prog = 247
                    TIMERA = 0
                ELSE
                    IF wk_prog = 247
                        SET_RENDER_OBJECT_VISIBLE wk_rend[7] FALSE
                        SET_RENDER_OBJECT_VISIBLE wk_rend[8] TRUE
                        wk_prog = 248
                        TIMERA = 0
                    ELSE
                        IF wk_prog = 248
                            SET_RENDER_OBJECT_VISIBLE wk_rend[8] FALSE
                            SET_RENDER_OBJECT_VISIBLE wk_rend[9] TRUE
                            IF LOCATE_CHAR_DISTANCE_TO_OBJECT wk_i wk_obj 2.5
                            AND NOT IS_CHAR_IN_ANY_CAR wk_i
                                GET_CHAR_HEALTH wk_i (wk_j)
                                wk_j -= 10
                                IF wk_j < 0
                                    wk_j = 0
                                ENDIF
                                SET_CHAR_HEALTH wk_i wk_j
                                TASK_SAY wk_i 358
                                TASK_PLAY_ANIM_SECONDARY wk_i "SHOT_partial_B" "PED" 4.0 FALSE TRUE TRUE FALSE -1
                            ENDIF
                            wk_prog = 249
                            TIMERA = 0
                        ELSE
                            SET_RENDER_OBJECT_VISIBLE wk_rend[9] FALSE
                            SET_RENDER_OBJECT_VISIBLE wk_rend[8] TRUE
                            wk_prog = 146
                            TIMERA = 0
                        ENDIF
                    ENDIF
                ENDIF
            ENDIF
        ENDIF

        // ---- volta ao repouso ----
        IF wk_prog >= 346
        AND wk_event = 0
            GOSUB WorkerFacePlayer
            IF TIMERA > 70
                IF wk_prog = 346
                    SET_RENDER_OBJECT_VISIBLE wk_rend[4] FALSE
                    SET_RENDER_OBJECT_VISIBLE wk_rend[5] FALSE
                    SET_RENDER_OBJECT_VISIBLE wk_rend[6] FALSE
                    SET_RENDER_OBJECT_VISIBLE wk_rend[8] FALSE
                    SET_RENDER_OBJECT_VISIBLE wk_rend[3] TRUE
                    wk_prog = 347
                    TIMERA = 0
                ELSE
                    IF wk_prog = 347
                        SET_RENDER_OBJECT_VISIBLE wk_rend[3] FALSE
                        SET_RENDER_OBJECT_VISIBLE wk_rend[0] TRUE
                        wk_prog = 0
                        wk_event = 1
                        TIMERA = 0
                    ENDIF
                ENDIF
            ENDIF
        ENDIF

        // ---- dano e colisao ----
        GET_OFFSET_FROM_OBJECT_IN_WORLD_COORDS wk_obj (-3.0) (-3.0) (-2.0) (wk_x wk_y wk_z)
        GET_OFFSET_FROM_OBJECT_IN_WORLD_COORDS wk_obj 3.0 3.0 2.0 (wk_x2 wk_y2 wk_z2)
        IF IS_EXPLOSION_IN_AREA -1 wk_x wk_y wk_z wk_x2 wk_y2 wk_z2
            GOSUB WorkerBlood
            wk_alive = 0
        ENDIF

        IF wk_alive = 1
            GET_OBJECT_COORDINATES wk_obj (wk_x wk_y wk_z)
            GET_NUMBER_OF_FIRES_IN_RANGE wk_x wk_y wk_z 1.5 (wk_j)
            IF wk_j > 0
                GOSUB WorkerBlood
                wk_alive = 0
            ENDIF
        ENDIF

        IF wk_alive = 1
            GET_OBJECT_HEALTH wk_obj (wk_j)
            IF wk_j < 1000
                GOSUB WorkerBlood
                wk_alive = 0
            ENDIF
        ENDIF

        // um pedestre pisou na cobra
        IF wk_alive = 1
            GET_OBJECT_COORDINATES wk_obj (wk_x wk_y wk_z)
            IF GET_RANDOM_CHAR_IN_SPHERE_NO_SAVE_RECURSIVE wk_x wk_y wk_z 1.1 FALSE -1 (wk_j)
                GOSUB WorkerBlood
                wk_alive = 0
            ENDIF
        ENDIF

        // veiculos perto passam por cima
        IF wk_alive = 1
            wk_k = 0
            wk_j = 1
            WHILE wk_j = 1
                IF GET_RANDOM_CAR_IN_SPHERE_NO_SAVE_RECURSIVE wk_x wk_y wk_z 10.0 wk_k TRUE (wk_i)
                    wk_k = 1
                    IF IS_VEHICLE_TOUCHING_OBJECT wk_i wk_obj
                        GOSUB WorkerBlood
                        wk_alive = 0
                        wk_j = 0
                    ENDIF
                ELSE
                    wk_j = 0
                ENDIF
            ENDWHILE
        ENDIF

        IF wk_alive = 0
            GOSUB WorkerCleanup
            GOSUB WorkerExit
        ENDIF
    ENDWHILE
    RETURN

// ---------------------------------------------------------------------------
WorkerFacePlayer:
    GET_CHAR_COORDINATES wk_i (wk_x2 wk_y2 wk_z2)
    GET_OBJECT_COORDINATES wk_obj (wk_x wk_y wk_z)
    GET_ANGLE_FROM_TWO_COORDS wk_x2 wk_y2 wk_x wk_y (wk_ang)
    SET_OBJECT_HEADING wk_obj wk_ang
    RETURN

// ---------------------------------------------------------------------------
WorkerBlood:
    GET_OBJECT_COORDINATES wk_obj (wk_x wk_y wk_z)
    IF IS_PLAYER_PLAYING 0
        GET_PLAYER_CHAR 0 (wk_j)
        ADD_BLOOD wk_x wk_y wk_z 0.0 0.0 0.0 100 wk_j
    ENDIF
    GOSUB WorkerRemoveAudio
    IF LOAD_3D_AUDIO_STREAM "SoundsQa/SNAKEDEATH.mp3" (wk_snd_die)
        SET_PLAY_3D_AUDIO_STREAM_AT_OBJECT wk_snd_die wk_obj
        SET_AUDIO_STREAM_VOLUME wk_snd_die wk_vol
        SET_AUDIO_STREAM_STATE wk_snd_die 1
    ENDIF
    SET_OBJECT_COLLISION wk_obj FALSE
    SET_RENDER_OBJECT_VISIBLE wk_rend[0] FALSE
    SET_RENDER_OBJECT_VISIBLE wk_rend[1] FALSE
    SET_RENDER_OBJECT_VISIBLE wk_rend[2] FALSE
    SET_RENDER_OBJECT_VISIBLE wk_rend[3] FALSE
    SET_RENDER_OBJECT_VISIBLE wk_rend[4] FALSE
    SET_RENDER_OBJECT_VISIBLE wk_rend[5] FALSE
    SET_RENDER_OBJECT_VISIBLE wk_rend[6] FALSE
    SET_RENDER_OBJECT_VISIBLE wk_rend[7] FALSE
    SET_RENDER_OBJECT_VISIBLE wk_rend[8] FALSE
    SET_RENDER_OBJECT_VISIBLE wk_rend[9] FALSE
    WAIT 1000
    RETURN

// ---------------------------------------------------------------------------
// Espera o jogador se afastar do local da morte antes de liberar a thread
// (mesma ideia do "Exit" do script original, mas com limite de tempo).
// ---------------------------------------------------------------------------
WorkerExit:
    wk_k = 0
    WHILE wk_k < 30
        WAIT 1000
        wk_k += 1
        IF IS_PLAYER_PLAYING 0
            GET_PLAYER_CHAR 0 (wk_j)
            IF NOT LOCATE_CHAR_DISTANCE_TO_COORDINATES wk_j wk_x wk_y wk_z 100.0
                wk_k = 30
            ENDIF
        ENDIF
    ENDWHILE
    RETURN

// ---------------------------------------------------------------------------
WorkerRemoveAudio:
    IF NOT wk_snd_atk = 0
        REMOVE_AUDIO_STREAM wk_snd_atk
        wk_snd_atk = 0
    ENDIF
    IF NOT wk_snd_idle = 0
        REMOVE_AUDIO_STREAM wk_snd_idle
        wk_snd_idle = 0
    ENDIF
    IF NOT wk_snd_die = 0
        REMOVE_AUDIO_STREAM wk_snd_die
        wk_snd_die = 0
    ENDIF
    RETURN

// ---------------------------------------------------------------------------
WorkerCleanup:
    GOSUB WorkerRemoveAudio
    IF DOES_OBJECT_EXIST wk_obj
        DELETE_OBJECT wk_obj
    ENDIF
    wk_obj = 0
    RETURN
}
