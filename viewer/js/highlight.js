// A tiny Java/SQL syntax highlighter (no dependencies). Returns escaped HTML.
import { esc } from './util.js';

const JAVA_KW = new Set(('abstract assert boolean break byte case catch char class const continue default do double else enum ' +
  'extends final finally float for goto if implements import instanceof int interface long native new package private ' +
  'protected public return short static strictfp super switch synchronized this throw throws transient try void volatile ' +
  'while var record yield sealed permits non-sealed true false null').split(' '));
const SQL_KW = new Set(('select from where insert into values update set delete merge join left right inner outer on and or not ' +
  'in is null as group by order having limit offset distinct union all exists case when then else end returning with ' +
  'upsert replace ignore count sum avg min max like between asc desc').split(' '));

const JAVA_TOKEN = /(\/\*[\s\S]*?\*\/|\/\/[^\n]*)|("""[\s\S]*?"""|"(?:[^"\\\n]|\\.)*"|'(?:[^'\\\n]|\\.)*')|(@[A-Za-z_][\w.]*)|(\b\d[\d_]*(?:\.\d+)?[lLfFdD]?\b)|([A-Za-z_$][\w$]*)/g;
const SQL_TOKEN = new RegExp(JAVA_TOKEN.source.replace('|\\/\\/[^\\n]*)', '|--[^\\n]*)'), 'g');

export function highlight(code, lang = 'java') {
  const kw = lang === 'sql' ? SQL_KW : JAVA_KW;
  let out = '';
  let last = 0;
  code = String(code ?? '');
  for (const m of code.matchAll(lang === 'sql' ? SQL_TOKEN : JAVA_TOKEN)) {
    out += esc(code.slice(last, m.index));
    last = m.index + m[0].length;
    const [t, com, str, ann, num, word] = m;
    if (com) out += `<span class="tok-com">${esc(t)}</span>`;
    else if (str) out += `<span class="tok-str">${esc(t)}</span>`;
    else if (ann && lang === 'java') out += `<span class="tok-ann">${esc(t)}</span>`;
    else if (num) out += `<span class="tok-num">${esc(t)}</span>`;
    else if (word && kw.has(lang === 'sql' ? word.toLowerCase() : word)) out += `<span class="tok-kw">${esc(t)}</span>`;
    else if (word && lang === 'java' && /^[A-Z]/.test(word)) out += `<span class="tok-type">${esc(t)}</span>`;
    else out += esc(t);
  }
  return out + esc(code.slice(last));
}
