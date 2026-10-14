# TwentyOneZ/Triton: integração com o upstream

## Base e histórico

- Upstream: [`tritonmc/Triton`, branch `v4`](https://github.com/tritonmc/Triton/tree/v4).
- Commit integrado: [`7e15d957835b3cf005017f3417198e15c4c12f47`](https://github.com/tritonmc/Triton/commit/7e15d957835b3cf005017f3417198e15c4c12f47), posterior à release 4.2.0.
- Ancestral comum: `b30bb44340584f52eb7d7d6c0f7ad7af04caf043`.
- Integração de 82 commits oficiais por merge real, preservando o commit personalizado `0ee8ceabc0cfa59edc510b4a877e54b5c929438a` e as autorias originais.
- Backup do fork personalizado: `backup/v4-custom-before-upstream-2026-10-13`.
- Branch de trabalho: `integration/upstream-v4-2026-10-13`.

Foram incorporados dialogs, livro de receitas, Folia, correções de login/configuração e desconexão, PLAYER_INFO, placas, wrappers de advancements para 26.3, refresh de inventários no BungeeCord, detecção de idioma no Folia e a camada de conversão da API para Adventure relocada. Nenhuma branch experimental foi mesclada.

## Funcionalidades adicionais do fork

### Encantamentos via PacketEvents

Ative `experimental-use-packetevents: true` e a tradução de itens na configuração do Triton. A implementação adicional de encantamentos está nesse caminho; os wrappers ProtocolLib personalizados e seus diagnósticos também foram preservados.

- Traduz `ENCHANTMENTS` e `STORED_ENCHANTMENTS`, incluindo livros encantados e descrições customizadas com placeholders Triton.
- Descrições translatable consultam o TranslationManager pela chave original, por exemplo `enchantment.minecraft.sharpness`. Níveis de 1 a 10 também podem usar `enchantment.level.N`.
- Sem entrada cadastrada, preserva a descrição translatable original. Níveis acima de 10 mantêm a representação romana personalizada.
- Mantém maldições vanilla em vermelho e o estilo não itálico do tooltip.
- Cria lore apenas na cópia enviada ao cliente. Não modifica os identificadores, níveis, quantidade ou componentes efetivos do item original.
- Respeita `TOOLTIP_DISPLAY`, inclusive tooltip inteiro e componentes ocultos. Se a lore estiver oculta, conserva a representação original do encantamento em vez de escondê-la e produzir lore invisível.
- Em versões anteriores a 1.21.5, respeita `showInTooltip`.
- Mantém a prevenção de duplicação por chaves translatable e correspondência exata das linhas geradas. Foi retirada a heurística de prefixos ingleses, que poderia confundir lore comum com encantamentos.
- O refresh deve partir do item original, como no fluxo oficial de inventários e receitas. Traduzir novamente a cópia já preparada não acrescenta novas linhas.

As auditorias `[EnchantAudit]` e os diagnósticos de itens ProtocolLib exigem `log-level: 2`. A inspeção detalhada de componentes e a reflexão não são executadas nos níveis normais de produção.

### Ofertas comerciais e receitas

`MERCHANT_OFFERS` traduz ambas as entradas e o resultado, preservando quantidades, usos, experiência, demanda, multiplicador e preço especial. O pacote só é marcado para reencodificação se um item mudar.

A implementação usa os stacks reais de `MerchantOffer`: em PacketEvents 2.14.0-triton, `getSecondInputCost()` retorna incorretamente a primeira entrada. O teste de regressão exige itens e quantidades distintos nas duas entradas e também cobre uma segunda entrada ausente.

Os handlers oficiais de receitas permanecem registrados junto ao handler de ofertas. `RecipeTranslationUtils` utiliza o mesmo tradutor de itens, preservando a receita original para refresh.

## Dependências e compilação

| Componente | Versão/coordenada |
| --- | --- |
| JDK de compilação | Java 25 |
| Bytecode base | Java 21, conforme o upstream |
| Paper API | `io.papermc.paper:paper-api:26.3.build.181-stable` |
| Adventure | `5.2.0` |
| PacketEvents | `com.github.retrooper:packetevents-*:2.14.0-triton` |
| ProtocolLib | `net.dmulloy2:ProtocolLib:5.5.0-SNAPSHOT` |
| Folia API | `26.1.2.build.8-stable` |
| Velocity | `4.2.0` |
| Libby | `2.1.0` |
| Gradle Wrapper | `9.8.1` |
| Shadow | `9.6.1` |
| plugin-yml | `0.9.0` |
| JUnit | `6.1.3` |

O [upstream exige a variante de PacketEvents adaptada para Adventure 5](https://github.com/tritonmc/Triton/commit/7b3929e981b1460e60cf1507bafe1eef8166ea77). Não substitua `2.14.0-triton` pelo artefato padrão `2.14.0`. O loader mantém a relocação de PacketEvents e a detecção/relocação condicional de Adventure.

O [upstream também alterou as coordenadas do ProtocolLib](https://github.com/tritonmc/Triton/commit/2ccf6fb6602bad5e7b0e801b573495215b37bb9f). `5.4.0-SNAPSHOT` não é a dependência usada nesta integração.

Foram validados os artefatos Maven e os SHA-256 dos cinco módulos PacketEvents contra `Dependency.java`. O carregamento Libby mantém o mirror e o repositório releases oficiais, além do repositório CodeMC snapshots personalizado. Checksums continuam obrigatórios para versões fixas; a exceção de snapshots do fork foi preservada.

Os módulos que compilam contra Paper usam `disableAutoTargetJvm()`, seguindo o padrão já existente para Folia e Velocity. Isso permite selecionar a API Java 25 com o compilador JDK 25 sem elevar o bytecode de todos os módulos. A execução no Paper 26.3 continua exigindo Java 25.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25.0.3'
.\gradlew.bat clean test build
```

Em Linux: `./gradlew clean test build` com JDK 25. Se o ambiente Windows precisar da cadeia de certificados do sistema, use somente nessa sessão:

```powershell
$env:JAVA_TOOL_OPTIONS = '-Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE'
```

Artefato instalável: **`build/libs/Triton-4.2.0-all.jar`**. O JAR sem `-all` é intermediário. O CI executa testes e build, disponibiliza `Triton-Paper` como artefato e não cria releases automaticamente.

## Validação em 2026-10-13

- Build completo com Gradle Wrapper e JDK 25.
- 140 testes aprovados, zero falhas; um teste de banners (`PatternsTest`) já desativado pelo upstream.
- 14 execuções de regressão adicionais: seis variantes de idioma, livros, maldição, encantamento customizado, placeholders de nome/lore, visibilidade moderna e antiga, deduplicação, ofertas comerciais e receitas.
- Dados independentes em `en_US`, `pt_BR`, `es_MX`, `ru_RU` e `fr_FR`; também coberta a grafia `ft_FR` informada inicialmente. Nenhuma dependência de arquivos ou banco do GoianiaSMP.
- Inicialização real no Paper **26.3-181-main@cc7ee9a**, com Java 25, em instância isolada, armazenamento local e mundo temporário.
- Download e relocação reais das dependências, habilitação do Triton e desligamento do servidor.
- Verificação dentro do Paper com conversão de itens Bukkit reais para PacketEvents e de volta: cinco idiomas, troca de idioma a partir do original, ausência de duplicação, respeito a encantamentos ocultos e preservação dos bytes/encantamentos do item Bukkit original.
- Conferência de `plugin.yml`, loader e módulos `.jarinjar` no JAR instalável.

Não foi feito teste visual com cliente Minecraft conectado, nem teste de ida e volta de inventário criativo. O aviso de inventário criativo já existente na configuração oficial continua aplicável. O teste real não usou o banco, mundo ou conjunto completo de plugins do Perspex. Os caminhos exclusivamente ProtocolLib, BungeeCord, Velocity e Folia foram compilados; não foram executados nesses servidores.

## Arquivos e futuros merges

Os pontos adicionais principais são `core/.../utils/ItemStackTranslationUtils.java`, `core/.../packetinterceptor/handlers/ItemPacketHandler.java`, `PacketEventsListener.java`, os diagnósticos em `triton-spigot/.../utils/ItemStackTranslationUtils.java` e `WrappedPatchedDataComponentMap.java`. Os testes estão em `ItemStackTranslationUtilsTest` e `ItemPacketHandlerTest`.

Mantenha o merge de histórico e execute a suíte após atualizar PacketEvents, Adventure, ProtocolLib ou Paper. Revise especialmente visibilidade de tooltips, a API de custos comerciais e a camada relocada. Retire o contorno de `getSecondInputCost()` somente depois de comprovar a correção da dependência e manter a regressão das duas entradas passando. Não versione caches, diretórios `bin` nem JARs gerados.
