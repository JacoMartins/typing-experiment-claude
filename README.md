# Trechos: teclado Android de trechos de palavra

Protótipo de teclado (`InputMethodService`) com dois modos:

- **QWERTY:** teclado normal. A tecla **⊞** troca para a grade.
- **Trechos:** grade 3×3 com os pedaços de palavra mais prováveis para continuar o texto
  (`pre` → `cisa`, `side`, `sen`…). O mais provável fica no centro, depois as bordas,
  depois os cantos. **⟳** mostra mais 9 trechos, **⌫** apaga o último trecho inteiro
  (segure para repetir) e **ABC** volta para o QWERTY.

Nos dois modos, a barra de cima completa a palavra atual, sem ligar para acentos
(`nao` → `não`), e acrescenta o espaço. Vírgula e ponto colam na palavra anterior.

O modo escolhido fica salvo: se você sair no modo trechos, o teclado volta nele.

## Como instalar

1. Baixe o APK no GitHub, em **Actions → Build APK → (última execução) → Artifacts →
   `trechos-debug-apk`**, ou compile com `./gradlew assembleDebug`.
2. Instale no celular. O Android vai pedir permissão para instalar apps de fontes desconhecidas.
3. Abra o app **Trechos** e toque em "Ativar o teclado" e depois em "Escolher o Trechos".

## Como a previsão funciona

Tudo roda no aparelho, sem internet (`app/src/main/java/dev/trechos/predict/`).

- **Memória pessoal (SQLite, `memoria.db`):** guarda o que você escreve, contado pelas até
  **3 palavras anteriores**. Para prever, o motor procura primeiro o que veio depois das últimas
  3 palavras; se não houver nada, depois das últimas 2; depois de 1 (*backoff*). As contagens
  perdem metade do peso a cada 60 dias sem uso, então o que você escreveu recentemente vale mais.
- **Vocabulário geral:** listas de frequência de português, inglês e espanhol, mais os bigramas
  de `assets/frases.txt`.
- **Cache da conversa:** palavras já usadas no texto atual ganham um pequeno bônus, o que ajuda a
  manter o assunto em textos longos.
- **Caracteres:** n-gramas de até 5 letras cobrem palavras fora do vocabulário.
- **Tokens da grade:** tudo é calculado como probabilidade de palavras. Cada trecho recebe a soma
  das probabilidades das palavras que começam com ele e é ordenado por probabilidade × letras
  economizadas.

Campos de senha e de número não são registrados. O aprendizado pode ser apagado na tela do app.

## Créditos dos dados

As listas `words_*.tsv` foram geradas com o [wordfreq](https://github.com/rspeer/wordfreq)
(Robyn Speer). Os dados do wordfreq são distribuídos sob a licença
[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/).
