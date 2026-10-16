// Exports of the current view: PNG, SVG, Mermaid, and .flowlens snapshots.
import { cssVar, esc } from './util.js';

export function toPng(cy) {
  // returns a base64 data URI
  return cy.png({ full: true, scale: 2, bg: cssVar('--bg'), output: 'base64uri', maxWidth: 12000, maxHeight: 12000 });
}

/** A standalone SVG of the visible elements, using their laid-out positions and styles. */
export function toSvg(cy) {
  const nodes = cy.nodes().filter(n => n.style('display') !== 'none');
  const edges = cy.edges().filter(e => e.style('display') !== 'none');
  if (!nodes.length) return null;
  const bb = nodes.union(edges).boundingBox({ includeLabels: true });
  const pad = 30;
  const w = Math.ceil(bb.w + pad * 2), h = Math.ceil(bb.h + pad * 2);
  const ox = pad - bb.x1, oy = pad - bb.y1;
  const font = '-apple-system, Segoe UI, Roboto, sans-serif';
  const out = [];
  out.push(`<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}" font-family="${font}">`);
  out.push(`<defs><marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="${cssVar('--edge-arrow')}"/></marker></defs>`);
  out.push(`<rect width="100%" height="100%" fill="${cssVar('--bg')}"/>`);
  for (const e of edges) {
    const s = e.sourceEndpoint(), t = e.targetEndpoint();
    const color = e.style('line-color');
    const dash = e.style('line-style') === 'dashed' ? ' stroke-dasharray="6 4"' : e.style('line-style') === 'dotted' ? ' stroke-dasharray="2 3"' : '';
    out.push(`<line x1="${(s.x + ox).toFixed(1)}" y1="${(s.y + oy).toFixed(1)}" x2="${(t.x + ox).toFixed(1)}" y2="${(t.y + oy).toFixed(1)}" stroke="${color}" stroke-width="1.6"${dash} marker-end="url(#arrow)"/>`);
    const label = e.data('label');
    if (label) {
      const mx = (s.x + t.x) / 2 + ox, my = (s.y + t.y) / 2 + oy;
      out.push(`<text x="${mx.toFixed(1)}" y="${my.toFixed(1)}" font-size="9" fill="${cssVar('--edge-label')}" text-anchor="middle" paint-order="stroke" stroke="${cssVar('--bg')}" stroke-width="3">${esc(label)}</text>`);
    }
  }
  for (const n of nodes) {
    const p = n.position(), nw = n.outerWidth() - 2, nh = n.outerHeight() - 2;
    const x = p.x + ox, y = p.y + oy;
    const fill = n.style('background-color');
    const stroke = n.style('border-color');
    const sw = parseFloat(n.style('border-width')) || 1;
    const type = n.data('type');
    if (type === 'branch') {
      out.push(`<polygon points="${x},${y - nh / 2} ${x + nw / 2},${y} ${x},${y + nh / 2} ${x - nw / 2},${y}" fill="${fill}" stroke="${stroke}" stroke-width="${sw}"/>`);
    } else if (type === 'db') {
      out.push(`<rect x="${x - nw / 2}" y="${y - nh / 2}" width="${nw}" height="${nh}" rx="${Math.min(nw, nh) / 2.5}" fill="${fill}" stroke="${stroke}" stroke-width="${sw}"/>`);
    } else {
      out.push(`<rect x="${x - nw / 2}" y="${y - nh / 2}" width="${nw}" height="${nh}" rx="6" fill="${fill}" stroke="${stroke}" stroke-width="${sw}"/>`);
    }
    const lines = String(n.data('display') || '').split('\n');
    const fs = parseFloat(n.style('font-size')) || 11;
    lines.forEach((ln, i) => {
      const ty = y + (i - (lines.length - 1) / 2) * (fs + 3) + fs / 3;
      out.push(`<text x="${x}" y="${ty.toFixed(1)}" font-size="${fs}" font-weight="600" fill="#fff" text-anchor="middle">${esc(ln)}</text>`);
    });
  }
  out.push('</svg>');
  return out.join('\n');
}

/** Mermaid flowchart text of the visible elements. */
export function toMermaid(cy, dir) {
  const nodes = cy.nodes().filter(n => n.style('display') !== 'none');
  const edges = cy.edges().filter(e => e.style('display') !== 'none');
  const ids = new Map();
  nodes.forEach((n, i) => ids.set(n.id(), 'n' + i));
  const q = s => '"' + String(s).replace(/"/g, '#quot;').replace(/\n/g, '<br/>') + '"';
  const lines = [`flowchart ${dir === 'TB' ? 'TD' : 'LR'}`];
  nodes.forEach(n => {
    const id = ids.get(n.id());
    const label = q(n.data('display'));
    switch (n.data('type')) {
      case 'endpoint': lines.push(`  ${id}([${label}])`); break;
      case 'branch': lines.push(`  ${id}{${label}}`); break;
      case 'throw': lines.push(`  ${id}>${label}]`); break;
      case 'db': lines.push(`  ${id}[(${label})]`); break;
      case 'external': lines.push(`  ${id}{{${label}}}`); break;
      default: lines.push(`  ${id}[${label}]`);
    }
  });
  edges.forEach(e => {
    const s = ids.get(e.data('source')), t = ids.get(e.data('target'));
    if (!s || !t) return;
    const label = e.data('label') ? `|${q(e.data('label'))}|` : '';
    const arrow = e.data('confidence') === 'high' || !e.data('confidence') ? '-->' : '-.->';
    lines.push(`  ${s} ${arrow}${label} ${t}`);
  });
  const cls = { endpoint: '#8b5cf6', db: '#16a34a', external: '#db2777', branch: '#d97706', throw: '#dc2626' };
  for (const [type, color] of Object.entries(cls)) {
    const members = nodes.filter(n => n.data('type') === type).map(n => ids.get(n.id()));
    if (members.length) lines.push(`  classDef ${type} fill:${color},color:#fff,stroke:#333`, `  class ${members.join(',')} ${type}`);
  }
  return lines.join('\n') + '\n';
}

/** A .flowlens snapshot: the graph plus a little metadata. */
export function toSnapshot(graph) {
  return JSON.stringify({ flowlensSnapshot: 1, savedAt: new Date().toISOString(), graph }, null, 0);
}

export function parseSnapshot(text) {
  const data = JSON.parse(text);
  const graph = data && data.flowlensSnapshot ? data.graph : data;
  if (!graph || !Array.isArray(graph.nodes)) throw new Error('Not a FlowLens snapshot or graph.json');
  return graph;
}
