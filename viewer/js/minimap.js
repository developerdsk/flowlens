// A small overview of the visible graph with the current viewport; click or drag to pan.
import { cssVar } from './util.js';

export class Minimap {
  constructor(canvas, getCy) {
    this.canvas = canvas;
    this.getCy = getCy;
    this.pending = false;
    const move = e => this.panTo(e);
    canvas.addEventListener('mousedown', e => { this.dragging = true; move(e); });
    window.addEventListener('mousemove', e => { if (this.dragging) move(e); });
    window.addEventListener('mouseup', () => { this.dragging = false; });
  }

  schedule() {
    if (this.pending) return;
    this.pending = true;
    requestAnimationFrame(() => { this.pending = false; this.draw(); });
  }

  geometry() {
    const cy = this.getCy();
    if (!cy) return null;
    const nodes = cy.nodes().filter(n => n.style('display') !== 'none');
    if (!nodes.length) return null;
    const bb = nodes.boundingBox();
    const W = this.canvas.width, H = this.canvas.height, pad = 8;
    const scale = Math.min((W - pad * 2) / Math.max(bb.w, 1), (H - pad * 2) / Math.max(bb.h, 1));
    const ox = pad + ((W - pad * 2) - bb.w * scale) / 2 - bb.x1 * scale;
    const oy = pad + ((H - pad * 2) - bb.h * scale) / 2 - bb.y1 * scale;
    return { cy, nodes, scale, ox, oy };
  }

  draw() {
    const ctx = this.canvas.getContext('2d');
    ctx.clearRect(0, 0, this.canvas.width, this.canvas.height);
    const g = this.geometry();
    this.canvas.hidden = !g || g.nodes.length < 12;
    if (!g) return;
    const { cy, nodes, scale, ox, oy } = g;
    nodes.forEach(n => {
      const p = n.position();
      ctx.fillStyle = n.style('background-color');
      const w = Math.max(2, n.outerWidth() * scale), h = Math.max(2, n.outerHeight() * scale);
      ctx.fillRect(p.x * scale + ox - w / 2, p.y * scale + oy - h / 2, w, h);
    });
    const ext = cy.extent();
    ctx.strokeStyle = cssVar('--accent');
    ctx.lineWidth = 1.5;
    ctx.strokeRect(ext.x1 * scale + ox, ext.y1 * scale + oy, ext.w * scale, ext.h * scale);
  }

  panTo(e) {
    const g = this.geometry();
    if (!g) return;
    const r = this.canvas.getBoundingClientRect();
    const mx = (e.clientX - r.left) * (this.canvas.width / r.width);
    const my = (e.clientY - r.top) * (this.canvas.height / r.height);
    const modelX = (mx - g.ox) / g.scale, modelY = (my - g.oy) / g.scale;
    const zoom = g.cy.zoom();
    g.cy.pan({ x: g.cy.width() / 2 - modelX * zoom, y: g.cy.height() / 2 - modelY * zoom });
  }
}
