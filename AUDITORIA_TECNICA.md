# Auditoria técnica — Rattlesnake (CLEO / GTA San Andreas)

Data da revisão final: **12/09/2026**

## Veredito

Os problemas identificados na auditoria inicial foram corrigidos nos fontes e os dois scripts foram recompilados. A arquitetura visual original foi preservada: cada tipo de cobra continua usando dez DFFs estáticos como quadros de animação, ligados por render objects a um objeto-base invisível. Isso evita reservar ou substituir um ID de modelo dedicado no GTA.

A validação estática completa passou, incluindo compilação dos dois fontes com Sanny Builder 4.2.0, correspondência entre os metadados dos `.cs` e os respectivos `.txt`, integridade estrutural dos assets e invariantes de segurança. Não havia uma instalação executável do GTA San Andreas/CLEO neste ambiente; portanto, comportamento e desempenho dentro do jogo ainda precisam ser confirmados manualmente.

## Escopo e decisão arquitetural

Foram mantidos, por variante:

- dez carregamentos `0F00` de modelos especiais;
- dez render objects `0F04`;
- alternância de visibilidade para formar a animação;
- o modelo original `1672` apenas como objeto-base invisível;
- os 20 DFFs e o TXD existentes no pacote.

Os DFFs separados não são arquivos duplicados acidentais. Eles são os quadros estáticos intencionais da animação e não devem ser convertidos para um único modelo animado.

## Correções implementadas

### 1. Validação de modelos e render objects

- Cada `0F00: load_special_model_dff` agora testa sucesso antes de armazenar o handle no cache.
- Os dez handles recuperados de `SnakeModels.ini` são rejeitados se forem nulos.
- Os dez retornos de `0F04` são validados antes de a cobra entrar no loop ativo.
- Uma falha de asset cancela o script ou o spawn de forma controlada, em vez de continuar com ponteiros inválidos.

### 2. Streaming sem espera infinita

O modelo-base é solicitado com `model.Load` e `038B: load_requested_models`. Se ele não estiver disponível após a tentativa, o spawn é cancelado, o modelo é liberado e o script tenta novamente depois de um intervalo. Foi removido o `repeat` sem limite que podia prender o thread em uma falha de streaming.

### 3. Cleanup idempotente

Foram criadas e usadas as rotinas `@RemoveAudio` e `@Cleanup`:

- streams só são removidos quando seus handles são válidos;
- os handles de áudio são zerados depois da remoção;
- o objeto só é destruído quando existe;
- o handle do objeto também é zerado;
- todas as rotas relevantes de saída passam pelo mesmo cleanup.

O loop ativo verifica `player.Defined(0)` antes de usar CJ. `@Exit` também abandona imediatamente o cooldown se o jogador deixar de estar definido e usa `wait 100`, sem busy loop.

### 4. Dano de mordida validado

O dano só é aplicado no quadro de impacto quando:

- o jogador ainda está definido;
- CJ está dentro do raio da mordida;
- CJ está a pé.

A vida resultante é limitada a zero, impedindo valor negativo. Entrar em um veículo ou afastar-se durante a animação não causa mais dano tardio indevido.

### 5. Buscas espaciais no lugar das enumerações globais

As varreduras recursivas completas dos pools em todo frame foram removidas:

- `0AE1` procura pedestres próximos à cobra;
- `0AE2` limita os veículos aos que estão numa esfera local;
- `0897` confirma colisão somente nos veículos pré-filtrados.

O custo agora depende principalmente das entidades próximas, não de todos os peds e veículos carregados no jogo. Ainda não foi possível medir frametime real.

### 6. Menos chamadas de visibilidade

As transições passaram a ocultar somente os quadros anteriores possíveis e exibir o próximo. Cada fonte caiu de aproximadamente 150 para **60 chamadas `0E31`**, preservando todos os dez quadros e todos os estados visuais.

### 7. Áudio, configuração e paths

- Os retornos de carregamento dos streams 3D são testados.
- `Volume` tem fallback `0.6` e clamp no intervalo `[0.0, 1.0]`.
- Os paths de Qb agora usam exatamente `SnakeB1`–`SnakeB10`, `SnakeAttackB.mp3` e `SnakeIdleB.mp3`.
- `Snake.ini` não usa comentários inline ambíguos.
- `SnakeModels.ini` é distribuído vazio e documentado como cache process-local; não contém mais ponteiros de uma execução antiga.

### 8. Build e validação reproduzíveis

Foi adicionado `tools/validate_release.py`, sem dependências externas. Ele verifica:

- quantidade de modelos especiais e render objects por fonte;
- limite de chamadas de visibilidade e ausência dos padrões antigos de varredura/cleanup;
- paths e casing exatos dos assets referenciados;
- cabeçalho e tamanho declarado dos DFF/TXD RenderWare;
- presença de frames MPEG válidos nos cinco MP3s;
- metadados de fonte embutidos nos `.cs` e sua correspondência com os `.txt`.

A workflow `.github/workflows/build-cleo.yml` usa Windows, baixa o Sanny Builder **4.2.0** oficial, confere o SHA-256 fixado do arquivo, aguarda os processos do compilador, restaura os fontes LF após a normalização feita pelo Sanny e executa a validação completa.

## Evidências da validação final

| Verificação | Resultado |
|---|---|
| `git diff --check` | passou |
| `python3 tools/validate_release.py --sources-only` | passou |
| Compilação de `SnakeQa.txt` com Sanny Builder 4.2.0 | passou |
| Compilação de `SnakeQb.txt` com Sanny Builder 4.2.0 | passou |
| `python3 tools/validate_release.py` com os novos `.cs` | passou |
| GitHub Actions, execução `34730133135` | passou |
| DFFs encontrados e validados | 20 |
| TXDs encontrados e validados | 1 |
| MP3s encontrados com frames válidos | 5 |
| Loads especiais por fonte | 10 |
| Render objects por fonte | 10 |
| Chamadas `0E31` por fonte | 60 |

Binários finais:

- `cleo/SnakeQa.cs`: 37.846 bytes; SHA-256 `a0cf556610cf5390b5a19933b3b4aff967de2f7fadc5037c18c3c450355ff5a8`;
- `cleo/SnakeQb.cs`: 44.696 bytes; SHA-256 `abd78fe971cf36a4ee55bf924a1c800889396f76a3ee0e7add97a9f70af8240d`.

## Compatibilidade e requisitos

O mod requer:

- GTA San Andreas clássico para Windows;
- ASI Loader e Mod Loader;
- CLEO 4;
- CLEO+ 1.2.0 ou mais novo, devido a `LOAD_SPECIAL_MODEL`;
- NewOpcodes.

Não foi validado nem anunciado suporte a mobile ou Definitive Edition. Hot reload de CLEO não é suportado: os handles dos modelos especiais são válidos somente no processo que os criou. Após instalar o mod ou alterar `Cobra`, é necessário fechar e abrir o jogo.

## Limitações residuais

1. **Sem teste de gameplay neste ambiente.** A compilação prova a validade sintática e a auditoria cobre os principais fluxos, mas não substitui um teste no engine.
2. **Sem benchmark real.** As enumerações globais foram eliminadas e o número de chamadas nativas de visibilidade caiu, mas o ganho de frametime ainda deve ser medido em jogo.
3. **Cache process-local.** `SnakeModels.ini` continua sendo o mecanismo de compartilhamento entre os dois scripts e reloads no mesmo processo. Instâncias simultâneas usando a mesma pasta e hot reload não são suportados.
4. **Duplicação entre Qa e Qb.** As duas máquinas de estado continuam separadas. Isso preserva o formato original e permite parâmetros/assets diferentes, mas futuras mudanças devem ser aplicadas e validadas nas duas cópias.

## Matriz recomendada de teste no jogo

1. iniciar nova partida e carregar um save existente;
2. recarregar um save sem fechar o processo e depois testar após restart completo;
3. visitar todos os pontos de spawn de cascavel no deserto;
4. testar `Cobra = 0` e `Cobra = 1` após fechar e abrir o jogo;
5. aproximar, afastar mais de 55 m e retornar ao mesmo spawn;
6. receber a mordida a pé e confirmar o dano apenas no quadro de impacto;
7. sair do raio ou entrar em veículo durante o ataque e confirmar ausência de dano;
8. matar CJ/recarregar o save durante ataque, áudio e cooldown;
9. matar a cobra com tiro, explosão e fogo;
10. atropelar a cobra com veículo do jogador e de NPC;
11. observar contato de pedestres com a cobra;
12. remover temporariamente um DFF, o TXD e um MP3 para confirmar falha controlada;
13. comparar frametime com e sem o mod em área com muitos peds e veículos;
14. monitorar logs de CLEO/CLEO+ durante sessões longas e múltiplos spawns.

## Conclusão

O release está **corrigido, otimizado e compilado**, com os assets e os binários sincronizados aos fontes. Os riscos críticos encontrados — ponteiros de modelo/render inválidos, cleanup incompleto, uso de jogador indefinido, espera de streaming infinita e scans globais por frame — foram tratados. A única etapa que não pôde ser concluída no ambiente automatizado foi a validação prática dentro do GTA San Andreas.
