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

Tudo roda no aparelho, sem internet (`app/src/main/java/dev/trechos/predict/Predictor.kt`):

- **Vocabulário:** listas de frequência de português, inglês e espanhol
  (`assets/words_*.tsv`, com peso maior para o português). Para cada trecho candidato,
  o motor soma a probabilidade de todas as palavras que começam com ele.
- **Contexto:** bigramas ("depois de X vem Y") de `assets/frases.txt` e do seu histórico.
- **Caracteres:** n-gramas de até 5 letras cobrem palavras fora do vocabulário.
- **Pontuação:** cada trecho é ordenado por probabilidade × letras economizadas. Se um
  trecho mais longo cobre quase todos os casos de um mais curto, o curto sai da grade.
- **Aprendizado:** cada palavra concluída vai para `historico.tsv`, no armazenamento
  privado do app. Campos de senha e de número não são registrados. O aprendizado pode ser
  apagado na tela do app.

Para mudar as línguas ou os pesos, edite `loadModel()` em `TrechosKeyboardService.kt`.

## Créditos dos dados

As listas `words_*.tsv` foram geradas com o [wordfreq](https://github.com/rspeer/wordfreq)
(Robyn Speer). Os dados do wordfreq são distribuídos sob a licença
[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/).
