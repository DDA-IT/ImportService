import '@testing-library/jest-dom/vitest';
import { configure } from '@testing-library/react';

// Onder belasting van de volledige suite (parallelle jsdom-workers) overschrijdt waitFor/findBy* de standaard
// asyncUtilTimeout (1000 ms) soms. Ruimere grens voor async utils; echte fouten falen nog steeds, alleen later.
configure({ asyncUtilTimeout: 5000 });

// jsdom implementeert <dialog>.showModal()/close() niet (of onvolledig). Minimale polyfill: showModal
// zet het `open`-attribuut (zodat de dialoog toegankelijk/zichtbaar is voor Testing Library), close
// haalt het weg. Focus trap en top-layer worden niet nagebootst; Escape wordt in tests met een
// 'cancel'-event gesimuleerd.
if (typeof HTMLDialogElement !== 'undefined') {
  HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
    this.setAttribute('open', '');
  };
  HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
    this.removeAttribute('open');
  };
}
