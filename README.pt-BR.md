# WazeTripper

<p align="center"><img src="docs/logo.png" width="200" alt="WazeTripper"></p>

[English](README.md) · **Português (Brasil)**

Modifica o app Waze para Android para que ele controle um **Tripper Pod da Royal Enfield** (o display de navegação da
Meteor 350) por Bluetooth Low Energy, sem app intermediário e sem ler notificações do Google Maps. As manobras, a
*próxima* manobra, as distâncias, o ETA e os alertas de radar vêm direto do motor de navegação do próprio Waze.

> **Qual Tripper?** A Royal Enfield tem dois produtos com "Tripper" no nome: o **Tripper Pod** (o display compacto de
> navegação, que aparece por Bluetooth como `RE_DISP`) e o **Tripper Dash** (outro aparelho, com hardware próprio e,
> até onde deu para ver, protocolo próprio). **Este projeto é somente para o Tripper Pod.** O Dash não é suportado e
> nunca foi testado. Daqui em diante, "Tripper" sempre significa o Tripper Pod.

![WazeTripper: a navegação curva a curva do Waze no Tripper Pod, o botão flutuante do Tripper no Waze e o painel de configurações](docs/flyer-pt.png)

## Sumário

- [Segurança](#segurança)
- [Contexto](#contexto)
  - [Estado](#estado)
  - [Recursos](#recursos)
  - [Veja também](#veja-também)
- [Instalação](#instalação)
  - [Dependências](#dependências)
- [Uso](#uso)
  - [Logs de trajeto](#logs-de-trajeto)
  - [Ajudando a testar](#ajudando-a-testar)
  - [Limitações conhecidas](#limitações-conhecidas)
- [Aviso legal](#aviso-legal)
- [Licença](#licença)

## Segurança

Este projeto controla o display de instrumentos de uma moto. Configure tudo parado e teste fora da moto antes de
depender dele pilotando; **não mexa no app enquanto pilota**.
O `scripts/framecheck.sh` confere o layout de bytes de cada pacote enviado ao Tripper sem nenhum hardware (inclui
pacotes capturados de um Tripper de verdade e trava o build), e o log **Detalhes** do app e os logs de trajeto mostram
o que foi enviado. Depois que você passar a pilotar com ele, trate-o como qualquer outro mostrador e mantenha os olhos
na estrada.

Modificar o Waze provavelmente viola os Termos de Uso dele. Esse risco é seu, no seu aparelho e na sua conta, e nada
aqui pode isentá-lo. Use por sua conta e risco.

## Contexto

É um projeto de hobby, experimental e não oficial, para os seus próprios aparelhos, publicado para estudo. Você traz a
sua cópia do Waze e o seu hardware. O Waze é decompilado, alguns hooks em smali são injetados (inicialização e
callbacks de navegação), um pequeno pacote Java ([`src/com/waze/wazetripper/`](src/com/waze/wazetripper)) é compilado
e enxertado, e o resultado é reassinado. Tudo isso acontece dentro de uma imagem Docker, sem recompilar os recursos do
Waze. O lado Java fala o protocolo BLE do Tripper ([`docs/PROTOCOL.md`](docs/PROTOCOL.md), em inglês).

O protocolo do Tripper foi levantado de forma independente, observando um Tripper de verdade e analisando, para
interoperabilidade, apps existentes do Tripper. Só uma configuração está confirmada até agora (abaixo); outras motos,
celulares e versões do Waze não foram testados.

### Estado

| | |
|---|---|
| Versão | `0.4.2` (veja [`CHANGELOG.md`](CHANGELOG.md)) |
| Testado em | Tripper Pod da Royal Enfield Meteor 350 · Waze **5.23.0.2** · Samsung Galaxy S23 |
| Versão do Waze | Somente a **5.23.0.2**. Os hooks usam nomes ofuscados, que mudam entre versões do Waze. |
| Verificado em hardware | link e navegação continuam com o Waze minimizado e a tela bloqueada; reconexão automática; ícone de radar `0x3C` |
| Ainda sem verificação em hardware na 0.4.x | telas de rota iniciada/recalculando, ícone de ligação, intensidade, voltar ao relógio do Tripper pela reconexão automática |
| Antigos, ainda sem verificação | gravação/exportação de logs de trajeto |

### Recursos

- Roda **dentro** do Waze modificado: um botão flutuante do Tripper abre o painel de conexão. O símbolo de Wi-Fi dele fica vermelho desconectado, laranja ao conectar e verde conectado.
- Pareamento com o PIN do Tripper e reconexão ao Tripper conhecido (automática ao abrir o Waze e após queda do link,
  por até 30 minutos; pode ser desligada).
- Navegação: manobra atual, **próxima manobra** (seta pequena), distância, saída de rotatória e uma linha inferior à
  sua escolha: distância total, tempo restante ou hora de chegada (12h/24h).
- Alertas de radar (câmeras, velocidade média, zonas de fiscalização) exibidos no Tripper a até 300 m, com opção para
  desligar ou escolher a codificação.
- Dia/noite segue o tema do Waze. Telas de rota iniciada e de recalculando, ícone de ligação enquanto o celular toca e
  intensidade do ícone conforme a distância.
- Sem rota: relógio nativo do Tripper ou bússola por GPS. Sincronização de hora e formato 12h/24h.
- **Logs de trajeto**: cada navegação é gravada em um arquivo exportável pelo painel, para conferir quais ícones
  apareceram errados ou faltaram.

### Veja também

- [`DEVELOPMENT.md`](docs/DEVELOPMENT.md) (em inglês) cobre o mecanismo completo de download, decompilação, patch,
  enxerto e assinatura, a arquitetura e como estender as verificações de pacotes.
- [`PROTOCOL.md`](docs/PROTOCOL.md) (em inglês) documenta o protocolo BLE do Tripper, com o nível de confiança de cada
  valor.
- [`CHANGELOG.md`](CHANGELOG.md) lista o que mudou em cada versão, e [`NOTICE.md`](NOTICE.md) traz os créditos.

## Instalação

O WazeTripper é montado pelos scripts em `scripts/`. A única coisa que você instala no computador é o Docker; toda a
ferramenta Android roda dentro de uma imagem fixada.

### Dependências

- **Docker**, em uma máquina que o rode (Linux, macOS ou Windows + WSL2).
- Um celular Android para instalar o APK montado, e o `adb` (ou um gerenciador de arquivos).
- Conexão de rede na primeira montagem: o `scripts/fetch-apk.sh` baixa o Waze **5.23.0.2** com o
  [apkeep](https://github.com/EFForg/apkeep) (APKPure por padrão, ou Google Play, com conta; veja `.env.example`).
  O APK nunca é versionado.

```bash
scripts/build-image.sh      # uma vez: cria a imagem com as ferramentas
scripts/all.sh              # baixa o Waze 5.23.0.2 -> decompila -> patch -> framecheck -> monta
# resultado: ./wazetripper.apk
scripts/framecheck.sh       # teste dos bytes dos pacotes, sem hardware (o build.sh também o roda como trava)
```

Não é preciso nenhum arquivo de configuração. Só se você quiser mudar a versão do Waze, a origem do download ou os
idiomas incluídos, copie o `.env.example` para `.env` antes e edite (os comentários dentro explicam cada opção).

O APK é assinado com uma chave de debug local, criada na primeira montagem. Como ela difere da assinatura da Play
Store, **desinstale o Waze oficial antes** (e entre de novo na conta depois). Depois:

```bash
adb install ./wazetripper.apk
```

## Uso

1. Ligue a moto, abra o Waze modificado e toque no botão flutuante do Tripper (o painel segue o idioma do Waze: português, espanhol, ou inglês para qualquer outro idioma).
2. Na primeira vez: toque em **Conectar**, digite o PIN mostrado no Tripper e confirme. O pareamento fica salvo.
3. Depois disso, o app procura o Tripper conhecido sozinho quando o Waze abre. Inicie uma rota no Waze e as manobras
   seguem para o Tripper.

Observação: o Tripper só anuncia por pouco tempo depois de ligar a ignição. Se você ligou a moto *antes* de abrir o
Waze e ele não conectou, desligue e ligue a ignição de novo com o Waze aberto.

### Logs de trajeto

Enquanto o Waze navega, o WazeTripper grava um log daquele trajeto no armazenamento privado do app (os últimos 30
trajetos ou cerca de 10 MB). No painel, **Exportar** salva em `Downloads/WazeTripper/` e abre o compartilhamento do
Android.

**O que vai no arquivo:** horários; os nomes das manobras do Waze e os bytes enviados ao Tripper; distâncias, ETA e
eventos de radar; eventos da conexão Bluetooth (que podem incluir o endereço Bluetooth do seu Tripper); as versões do
app, do Waze e do Android e o modelo do celular. **As coordenadas da rota nunca são gravadas.** Leia o arquivo antes
de enviá-lo a alguém.

### Ajudando a testar

Testar exige o seu próprio APK (veja [Instalação](#instalação)). Por favor, não compartilhe APKs montados. Depois de
uma viagem, exporte o log e abra uma issue com o modelo *Trip log report*, ou envie em particular ao mantenedor.
Relatos de manobras com ícone errado ou sem ícone são os mais úteis (procure as linhas `sem traducao pro Tripper`).

### Limitações conhecidas

- Somente Waze 5.23.0.2; o protocolo do Tripper está só parcialmente documentado (veja as questões em aberto em
  [`docs/PROTOCOL.md`](docs/PROTOCOL.md)).
- Voltar ao relógio nativo do Tripper (bússola desligada, rota encerrada) sem derrubar o link ainda não foi resolvido:
  o Tripper derruba o link de 4 a 5 s depois que uma tela deixa de ser reenviada e, após essa queda, volta a anunciar
  na hora, então a reconexão automática o recupera alguns segundos depois (já no relógio).
- No modo de radar *Experimental*, a distância vai nos bytes `[8-9]`, uma hipótese não verificada; se o Tripper os
  ignorar, só o ícone de radar aparece. O modo *Compatível* põe a distância na linha de baixo, escondendo o total da
  rota.
- Ainda não se sabe o significado do valor de "zona de fiscalização" do Waze, então ele só acende o indicador de
  radar, sem distância.

## Aviso legal

Sem afiliação, endosso ou ligação com Waze, Google ou Royal Enfield. "Waze", "Royal Enfield", "Tripper Pod" e
"Tripper Dash" são marcas dos respectivos donos, citadas aqui apenas para descrever compatibilidade.

Este repositório não contém código, recursos nem dados do Waze ou da Royal Enfield. Ele traz uma esteira de build e um
pequeno pacote injetado que rodam sobre a sua própria cópia do Waze, obtida de forma legítima e baixada pelo
`fetch-apk.sh` na hora de montar. Nada proprietário é redistribuído, e o APK que você montar **não deve** ser
redistribuído. O protocolo BLE do Tripper foi levantado de forma independente, para interoperabilidade, e não vem de
documentação nem de código da Royal Enfield.

## Licença

[Apache-2.0](LICENSE)

A licença cobre apenas o código próprio deste repositório: a esteira de build e o pacote injetado
`com.waze.wazetripper`. Ela não licencia, e não pode licenciar, nada do Waze nem da Royal Enfield. Veja o
[Aviso legal](#aviso-legal).

Construído sobre o **[wazeology](https://github.com/agstrc/wazeology)**, de agstrc (Apache-2.0), que fornece a
abordagem de patch e a esteira de build reaproveitada aqui. Veja [`NOTICE.md`](NOTICE.md).
