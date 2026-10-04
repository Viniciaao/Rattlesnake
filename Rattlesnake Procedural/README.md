# Rattlesnake Procedural

Versão **procedural** do mod [Rattlesnake](../README.md): em vez de pontos fixos
no mapa, as cobras nascem em volta do jogador, somente em solo natural e com
várias regras de naturalidade configuráveis.

O comportamento das cobras é o mesmo do mod original (parado, língua, aviso,
ataque, volta ao repouso, áudio 3D, sangue e reações a dano). O que muda é
*como* e *onde* elas aparecem — e quantas podem existir ao mesmo tempo.

O script é escrito em **GTA3Script** (compilador [gta3sc](https://github.com/thelink2012/gta3sc)),
não em Sanny Builder.

## Como funciona

- Uma thread **gerente** lê o INI, sorteia a chance, procura um ponto válido em
  volta do jogador e cria o objeto-base invisível da cobra.
- Cada cobra é cuidada por uma thread **SnakeWorker** (criada com
  `STREAM_CUSTOM_SCRIPT_FROM_LABEL`), então várias cobras rodam ao mesmo tempo,
  cada uma com sua própria animação e seus próprios sons.
- As cobras são removidas sozinhas quando o jogador se afasta mais do que
  `DespawnDistance` (55 m por padrão) ou quando o mod é desligado.

### Regras para escolher o local

1. **Raio**: o ponto sorteado fica entre `MinDistance` e `SpawnRadius` metros do
   jogador (18 m a 40 m por padrão).
2. **Chão natural**: um raio vertical é lançado (`0D3A`) e o tipo de superfície
   da colisão (`0D3C`) precisa estar na lista permitida — no modo padrão, areia,
   grama, terra, vegetação, bosque, rocha e campo (veja `Surfaces` no INI).
3. **Inclinação**: a normal do terreno precisa ter `Z >= 0.75`, evitando
   paredões e rampas.
4. **Cidade**: com `InCities = 0`, o ponto não pode estar dentro de Los Santos,
   San Fierro ou Las Venturas (`GET_CITY_FROM_COORDS`).
5. **Câmera**: com `AvoidCameraView = 1`, a cobra não nasce dentro do campo de
   visão da câmera — assim ela não "aparece" na frente do jogador.
6. **Teto**: também é lançado um raio de cima para baixo para evitar nascer
   embaixo de pontes, marquises e telhados.
7. **Separação**: o ponto precisa estar a pelo menos 25 m de outra cobra viva.

Se nenhuma das 8 tentativas por verificação passar, o script simplesmente tenta
de novo na próxima (`CheckInterval`). Com `Debug = 1` ele mostra na tela o tipo
de superfície encontrado e o motivo de cada recusa, o que ajuda a calibrar as
regras.

## Requisitos

- GTA San Andreas clássico para PC (o release original, v1.0 US)
- ASI Loader e Mod Loader
- CLEO 4
- CLEO+ **1.2.0 ou mais novo** (`LOAD_SPECIAL_MODEL`, `GET_COLLISION_BETWEEN_POINTS`,
  `STREAM_CUSTOM_SCRIPT_FROM_LABEL` e `RANDOM_PERCENT` são usados pelo script)
- NewOpcodes (já vem no pacote SA Essentials; o script usa `0AE1`/`0AE2`, que o
  CLEO 4 também fornece)

O pacote [SA] Essentials traz toda essa base: <https://www.mixmods.com.br/2019/06/sa-essentials-pack/>

Não use em Android/iOS nem na Definitive Edition.

## Instalação

1. Instale os requisitos acima.
2. Copie a pasta `Rattlesnake Procedural` completa para dentro de `modloader`,
   mantendo `cleo`, `ModelsQa` e `SoundsQa` juntos.
3. Feche e abra o jogo por completo.

**Não instale a versão de locais fixos (`SnakeQa`/`SnakeQb`) junto com esta** —
as duas gerariam cobras ao mesmo tempo. Os dois pacotes usam os mesmos modelos e
sons, então não misture as pastas de assets.

Não renomeie os arquivos DFF, TXD e MP3: os caminhos e o uso de maiúsculas e
minúsculas fazem parte do funcionamento (e são verificados pelo validador).

## Configuração

`cleo/SnakeProcedural.ini`:

| Chave | Padrão | Descrição |
| --- | --- | --- |
| `Enabled` | `1` | Liga/desliga o sistema. Com `0`, as cobras vivas são removidas. |
| `Chance` | `20` | Chance (%) de tentar gerar uma cobra a cada verificação. |
| `CheckInterval` | `2000` | Intervalo entre verificações, em ms (mínimo 500). |
| `MaxSnakes` | `3` | Cobras simultâneas (1 a 5). |
| `InCities` | `1` | `1` permite nascer dentro das cidades; `0` só no interior. |
| `Surfaces` | `1` | `0` = só areia/grama/terra, `1` = naturais, `2` = qualquer solo. |
| `AvoidCameraView` | `1` | Evita nascer dentro do campo de visão da câmera. |
| `SpawnRadius` | `40.0` | Distância máxima do jogador, em metros (limite: 40). |
| `MinDistance` | `18.0` | Distância mínima do jogador, em metros. |
| `DespawnDistance` | `55.0` | A cobra some quando o jogador passa dessa distância (limite: 55). |
| `Volume` | `0.6` | Volume dos sons, de `0.0` a `1.0`. |
| `Debug` | `0` | `1` mostra o aviso de início e o resultado de cada tentativa de geração. |

Os valores podem ser editados com o jogo aberto: o script relê o arquivo a cada
verificação. Aumentar `MaxSnakes` em jogo passa a valer a partir da próxima
verificação; diminuir não mata as cobras que já existem.

Se o arquivo `.ini` ou alguma chave não existir, cada leitura cai no valor padrão
da tabela acima — inclusive as chaves de número inteiro. Isso é necessário
porque o opcode de INI da CLEO escreve `0x80000000` na variável quando não
encontra uma chave INT (`Volume = 0.6` & co. são FLOAT e nem são tocados), então
toda leitura de INT no script tem o padrão declarado no próprio `IF NOT`.

### Modos de superfície

O tipo de superfície vem da colisão do chão (`eSurfaceType` do GTA SA). Os
valores usados pelo script:

| Modo | Superfícies aceitas |
| --- | --- |
| `0` — mínimo | grama (`9`–`20`, `80`–`82`, `160`), terra/lama (`24`–`27`, `123`, `124`, `153`), areia (`28`–`34`, `74`–`79`) |
| `1` — naturais (padrão) | tudo do modo 0 + vegetação/bosque/rocha/campo (`21`, `22`, `23`, `35`–`37`, `40`, `41`, `83`, `84`, `109`–`115`, `125`, `126`, `128`–`133`, `143`, `145`, `146`–`153`) |
| `2` — qualquer solo | qualquer superfície sólida, exceto água/riverbed (`38`, `39`, `96`–`100`, `154`–`157`) e superfícies que não são chão (`62`, `63`, `177`) |

Tudo o que é asfalto, calçada, concreto, trilho ou aeroporto (`1`–`8`, `89`,
`134`–`144`, `178`) fica de fora em todos os modos — por isso o modo padrão é o
único que combina "pode nascer na cidade" com "nunca em cima do asfalto".

### Nenhuma cobra nasceu? Como descobrir o motivo

O script não falha em silêncio. Com `Debug = 1`, cada etapa deixa rastro na tela;
além disso o próprio mod escreve `cleo\Rattlesnake_procedural.log`, **reescrito a
cada partida** (apague o arquivo antes de abrir o jogo para o teste ser limpo).
O que o log diz:

- **arquivo não existe (ou está vazio) depois de abrir o jogo** — o script nem
  chegou a rodar: confira se `Snake_Procedural.cs` está em `CLEO\` (não em
  subpastas), se o CLEO/ASI Loader está ativo e se o jogo é a versão de PC.
- **a primeira linha é `=== Rattlesnake Procedural v2 ===`** — confirma que o
  `.cs` em uso é esta versão corrigida (a v1 recusava todas as tentativas com
  motivo 3, veja "Armadilhas do gta3script" abaixo).
- **só o cabeçalho (4 linhas)** — o script rodou e parou antes de criar as
  threads; a tela mostra o motivo em vermelho (CLEO+ ausente/antigo, modelos).
- **linhas `tentativa ...`** — o gerente está procurando lugar: `surf` é o
  material que o jogo devolveu e `ok 0` é recusa, com o motivo no fim da linha.
  Cada tentativa vem seguida de uma segunda linha (`ponto ... | z ... | cp ... |
  nz ... | ent ...`) com o raio vertical usado, o ponteiro do colpoint, a normal
  do chão e a entidade atingida (`ent 0` = o raio não achou nada; tentativa
  recusada pela câmera nem chega a lançar o raio).
- **linhas `cobra criada em ...`** — o mod chegou a criar cobras (aparecem mesmo
  com `Debug = 0`, de propósito).

| Mensagem | O que significa / o que fazer |
| --- | --- |
| `Rattlesnake Procedural: 10 quadros, N threads, chance X%` | O gerente carregou os 10 quadros e criou as threads. Se esta mensagem não aparece, o problema é anterior a ela (veja as linhas abaixo). |
| `...CLEO+ nao encontrado.` | O `CLEO+.cleo` não está na pasta `CLEO`. O mod precisa do CLEO+ 1.2.0 ou mais novo. |
| `...precisa do CLEO+ 1.2.0 ou mais novo.` | O CLEO+ instalado é antigo demais. Atualize. |
| `...falha ao carregar ModelsQa\Snake*.dff` | Os modelos não estão onde o script procura. As pastas `ModelsQa` e `SoundsQa` precisam ficar na **raiz do GTA San Andreas**, ao lado do `gta_sa.exe`. |
| `...falha ao criar as threads (CLEO+ 1.2.0+?).` | O `STREAM_CUSTOM_SCRIPT_FROM_LABEL` não criou nenhuma thread. CLEO+ ausente/antigo, ou `Snake_Procedural.cs` de uma versão diferente do `Snake_Procedural.sc` (apague e copie o pacote inteiro de novo). |
| `Rattlesnake: tentativa N surf M ok 1 motivo 0` | Uma tentativa que passou em todas as regras: a cobra foi criada ali. |
| `Rattlesnake: tentativa N surf M ok 0 motivo 1` | O ponto sorteado estava dentro do campo de visão da câmera (a cobra nasceria na tela). Com `AvoidCameraView = 1` isso é normal; se *todas* as tentativas caírem aqui, teste com `AvoidCameraView = 0`. |
| `... motivo 2` | O raio vertical não achou chão nenhum (coordenada em interior/lugar sem colisão). |
| `... motivo 3` | O jogo não devolveu um material de superfície válido (0 ou acima de 178): o colpoint não foi preenchido e a tentativa é descartada. A linha `ponto ... cp ...` logo abaixo mostra o ponteiro usado; `cp 00000000` confirma que o CLEO+ recusou o ponteiro. |
| `... motivo 4` | O material do chão não é permitido pelo modo `Surfaces` (quase sempre asfalto/calçada/concreto com `Surfaces = 0` ou `1`). Teste com `Surfaces = 2` para ver se a cobra nasce. |
| `... motivo 5` | Terreno íngreme demais (`> 0.75` de inclinação no vetor normal). Se vier junto com `nz 0.00` **e** `cp 00000000`, não é o terreno: o buffer do colpoint não foi preenchido. |
| `... motivo 6` | O ponto está em Los Santos/San Fierro/Las Venturas e `InCities = 0`. |
| `... motivo 9` | O jogador não está em jogo (carregando, menu, missão com o ped trocado). |
| `Rattlesnake: nenhum ponto valido (8 tentativas)` | As 8 tentativas daquela verificação falharam — os motivos de cada uma vieram nas linhas anteriores. |
| `Rattlesnake: objeto da cobra nao existe (pedido descartado).` | O objeto invisível foi criado e desapareceu antes da thread pegá-lo (algum script ou o próprio jogo limpou objetos "no save"). |

Também vale conferir se o `Snake_Procedural.cs` está em `CLEO\` (não dentro de
subpastas) e se o CLEO o listou como carregado no log dele (`cleo\cleo.log`): um
`.cs` na pasta errada simplesmente não roda, e aí nenhuma mensagem aparece.

## Compilação

A fonte é `cleo/Snake_Procedural.sc` e deve ser compilada com o gta3sc:

```bash
# Linux/macOS (compila o gta3sc na revisao fixada, se necessario)
"Rattlesnake Procedural/tools/build.sh"

# verifica tambem o pacote e se o .cs commitado esta atualizado
"Rattlesnake Procedural/tools/build.sh" --verify
```

```powershell
# Windows (requer git, cmake e um compilador C++; use GTA3SC=... para reaproveitar)
powershell -ExecutionPolicy Bypass -File "tools\build.ps1"
```

Flags usadas (também em `tools/build.sh`):

```
gta3sc compile cleo/Snake_Procedural.sc --config=gtasa --cs --guesser \
    -fno-entity-tracking -fbreak-continue \
    --add-config="./tools/cleo_plus_commands.xml" -o cleo/Snake_Procedural.cs
```

- `tools/cleo_plus_commands.xml` define os opcodes do CLEO+ usados pelo script
  (copiados do próprio CLEO+; veja `tools/generate_cleo_plus_config.py`), além
  dos pseudo-comandos `TRUE`/`RETURN_TRUE`/`RETURN_FALSE`.
- `-fno-entity-tracking` desliga a checagem de tipos de entidade do compilador:
  as anotações de entidade do XML oficial do CLEO+ têm erros (`0xE30`, `0xD11`)
  e o script reutiliza variáveis temporárias de propósito.
- `-fbreak-continue` habilita `BREAK` dentro de `WHILE`.

O bytecode compilado (`cleo/Snake_Procedural.cs`) é versionado: quem instala o
mod não precisa compilar nada.

## Validação

```bash
python3 "Rattlesnake Procedural/tools/validate_release.py"
```

O validador confere: a fonte (sintaxe mínima, 10 quadros, nenhuma coordenada
fixa do mod antigo, threads filhas que nunca terminam sozinhas), o INI (todas as
chaves usadas, limites de 40 m e 55 m), o XML de opcodes, os assets (RenderWare
e MPEG válidos, com o uso exato de maiúsculas/minúsculas) e o `.cs` publicado
(com `--compiled`, compara com uma compilação nova).

Ele também protege três limites do SCM/CLEO que o compilador não verifica:

| Invariante | Limite | Por quê |
| --- | --- | --- |
| Variáveis locais por escopo | 32 | A CLEO dá 32 variáveis por script (`0@`–`31@`); `32@`/`33@` são os timers logo depois na memória do thread. O gerente usa 31 e cada worker 29 |
| `GOSUB` aninhados | 8 | A VM tem uma pilha fixa de sub-rotinas: mais que isso sem `RETURN` derruba o jogo. O script chega a 3 |
| Padrão em toda leitura INT do INI | — | O opcode de INI escreve `0x80000000` na variável quando a chave não existe |

A CI (`/.github/workflows/build-procedural.yml`) compila o gta3sc e o script em
cada pull request, valida o pacote e atualiza o `.cs` versionado.

### Boas práticas seguidas (e por quê)

- **Threads filhas nunca terminam sozinhas** (`TERMINATE_THIS_CUSTOM_SCRIPT` só
  existe no gerente antes de criar qualquer worker): a CLEO mantém um ponteiro
  para cada filho no pai, e um filho que se remove deixa esse ponteiro pendurado.
- **Cada cobra é uma thread filha** (`STREAM_CUSTOM_SCRIPT_FROM_LABEL`), o que dá
  variáveis isoladas por cobra sem gastar uma cópia do bytecode — o filho
  compartilha o bloco de código do pai, não a memória de variáveis.
- **Buffer de colisão em `DUMP` + `GET_LABEL_POINTER`** em vez de
  `ALLOCATE_MEMORY`: sem fragmentar a memória do processo e sem risco de
  vazamento se o script for interrompido.
- **Comunicação entre as threads por `SET/GET_CLEO_SHARED_VAR`** (as 1024
  variáveis compartilhadas da CLEO), com uma thread por cobra lendo o seu próprio
  trabalho; nenhum laço espera por outro.
- **`WAIT` sempre presente**: o gerente espera `CheckInterval` e os workers
  esperam por frame, então o mod nunca prende o processamento do jogo.
- **Sem repetição de código**: as sub-rotinas (`ManagerFindSpot`,
  `WorkerSnake`, `WorkerCleanup`...) são chamadas por `GOSUB`, dentro do limite de
  aninhamento.
- **Um escopo por thread**, com prefixos `mg_`/`wk_` para deixar claro o que é do
  gerente e o que é de uma cobra.

## Limitações conhecidas

- A escolha do chão é heurística: usa o material da colisão, não o tipo de zona
  do mapa. `Debug = 1` mostra o material e o motivo de cada recusa (veja a
  tabela de mensagens acima).
- Não nascem cobras em solo sem material natural dentro do raio — em bairros
  muito urbanos, sem parques nem grama, o mod simplesmente fica quieto (que é o
  comportamento desejado).
- Depois de uma cobra morrer, a thread espera o jogador se afastar até 100 m (ou
  30 s) antes de liberar espaço para outra, como no script original.
- O `SnakeModels.ini` do mod original não é usado: os 10 quadros são carregados
  uma vez pela thread gerente e reaproveitados por todas as cobras.

### Armadilhas do gta3script (leia antes de mexer no `.sc`)

Duas coisas que o compilador aceita sem reclamar, mas que quebram o script em
tempo de execução. As duas são barradas por `tools/validate_release.py`:

1. **Comando que não é condição dentro de `IF`/`AND`/`WHILE`.** O gta3sc compila
   `IF GET_COLPOINT_SURFACE ...` sem erro, mas quem decide o desvio é o
   resultado da última condição *de verdade* — o `IF` nunca dá o que você
   espera. Foi exatamente isso que fazia **todas** as tentativas de nascimento
   serem recusadas com `motivo 3` (nenhuma cobra nascia em lugar nenhum). No
   CLEO+ só `0xD3A` (`GET_COLLISION_BETWEEN_POINTS`) é condição; `0xD3C`
   (`GET_COLPOINT_SURFACE`), `0xD3B` e `0xD3E` são comandos comuns: chame solto
   e teste o valor devolvido.
2. **Constante como argumento de comando formatado.** Como `CONST_INT FRAMES`
   não é uma variável, o gta3sc emite o *nome* da constante como texto no meio
   dos parâmetros, desalinhando todos os `%i`/`%.1f` da linha. Passe o valor por
   uma variável (`mg_k = FRAMES` e use `mg_k`).

## Créditos

- Mod original (scripts, comportamento): ArtemQa146
- Modelos, quadros de animação e sons: Tarzan_3
- Versão procedural e porte para GTA3Script: este repositório
