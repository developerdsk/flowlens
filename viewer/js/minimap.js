// A small overview of the visible graph with the current viewport; click or drag to pan.
// Node geometry is cached after each layout; pan/zoom only repaints from the cache.
import { cssVar } from './util.js';

export class Minimap {
  constructor(canvas, getCy) {
    this.canvas = canvas;
    this.getCy = getCy;
    this.pending = false;
    this.geo = null;
    const move = e => this.panTo(e);
    canvas.addEventListener('mousedown', e => { this.dragging = true; move(e); });
    window.addEventListener('mousemove', e => { if (this.dragging) move(e); });
    window.addEventListener('mouseup', () => { this.dragging = false; });
  }

  /** Recompute node rectangles (after a layout, filter or theme change). */
  invalidate() {
    this.geo = null;
    this.schedule();
  }

  schedule() {
    if (this.pending) return;
    this.pending = true;
    requestAnimationFrame(() => { this.pending = false; this.draw(); });
  }

  geometry() {
    if (this.geo) return this.geo;
    const cy = this.getCy();
    if (!cy) return null;
    const nodes = cy.nodes().filter(n => n.style('display') !== 'none');
    if (!nodes.length) return null;
    const bb = nodes.boundingBox();
    const W = this.canvas.width, H = this.canvas.height, pad = 8;
    const scale = Math.min((W - pad * 2) / Math.max(bb.w, 1), (H - pad * 2) / Math.max(bb.h, 1));
    const ox = pad + ((W - pad * 2) - bb.w * scale) / 2 - bb.x1 * scale;
    const oy = pad + ((H - pad * 2) - bb.h * scale) / 2 - bb.y1 * scale;
    const rects = nodes.map(n => {
      const p = n.position();
      const w = Math.max(2, n.outerWidth() * scale), h = Math.max(2, n.outerHeight() * scale);
      return { x: p.x * scale + ox - w / 2, y: p.y * scale + oy - h / 2, w, h, color: n.style('background-color') };
    });
    this.geo = { cy, count: nodes.length, scale, ox, oy, rects };
    return this.geo;
  }

  draw() {
    const ctx = this.canvas.getContext('2d');
    ctx.clearRect(0, 0, this.canvas.width, this.canvas.height);
    const g = this.geometry();
    this.canvas.hidden = !g || g.count < 12;
    if (!g) return;
    for (const r of g.rects) {
      ctx.fillStyle = r.color;
      ctx.fillRect(r.x, r.y, r.w, r.h);
    }
    const ext = g.cy.extent();
    ctx.strokeStyle = cssVar('--accent');
    ctx.lineWidth = 1.5;
    ctx.strokeRect(ext.x1 * g.scale + g.ox, ext.y1 * g.scale + g.oy, ext.w * g.scale, ext.h * g.scale);
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
