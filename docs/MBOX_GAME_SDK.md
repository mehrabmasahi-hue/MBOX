# M BOX HTML Game Contract

A `.123T` package is a ZIP archive with the extension changed to `.123T`.
It must contain `index.html` at its root or within up to five nested folders.

The Display app opens the extracted `index.html` in a full-screen WebView.
Every ~33 ms it updates `window.MBOX_INPUT` and emits:

```js
window.dispatchEvent(new CustomEvent('mboxinput', {
  detail: window.MBOX_INPUT
}));
```

The object contains `players`, `count`, and convenience keys such as `player1`, `player2`.
Each player's input object has boolean keys: `up`, `down`, `left`, `right`, `a`, `b`,
`x`, `y`, `gas`, `brake`, and `nitro`.

The app also emits `mboxstart` after its 5-to-1 launch countdown. A game can pause its
initial logic until this event, or simply start immediately after page load.
