const pdfAssets = '/webjars/pdfjs-dist/6.5.438/';
let dialog;
let selected;
let pdfModule;

function element(tag, text, className) {
    const item = document.createElement(tag);
    if (text !== undefined) item.textContent = text;
    if (className) item.className = className;
    return item;
}

export function citationLink(citation, label) {
    const query = new URLSearchParams({sourceDocument: citation.documentId});
    if (citation.versionId) query.set('sourceVersion', citation.versionId);
    if (citation.chunkId) query.set('sourceChunk', citation.chunkId);
    if (citation.line) query.set('sourceLine', citation.line);
    const link = element('a', label, 'evidence-link');
    link.href = '/knowledge.html?' + query;
    link.dataset.evidenceLink = '';
    return link;
}

function setupDialog() {
    if (dialog) return;
    dialog = element('dialog', undefined, 'evidence-drawer');
    dialog.setAttribute('aria-labelledby', 'evidenceHeading');
    dialog.innerHTML = '<div class="evidence-layout"><header class="evidence-header"><div>'
        + '<h2 id="evidenceHeading">原文</h2><p class="evidence-meta"></p></div>'
        + '<form method="dialog"><button class="btn btn-secondary" type="submit">关闭</button></form></header>'
        + '<div class="evidence-content"></div></div>';
    dialog.addEventListener('close', release);
    document.body.append(dialog);
}

function release() {
    if (!selected) return;
    const active = selected;
    selected = undefined;
    active.abort.abort();
    active.render?.cancel();
    if (active.loading) active.loading.destroy().catch(() => {});
}

export async function openEvidence(query) {
    setupDialog();
    release();
    const request = new URLSearchParams({documentId: query.get('sourceDocument')});
    for (const [key, target] of [['sourceVersion', 'versionId'], ['sourceChunk', 'chunkId'], ['sourceLine', 'line']]) {
        if (query.get(key)) request.set(target, query.get(key));
    }
    const active = {abort: new AbortController()};
    selected = active;
    const heading = dialog.querySelector('h2');
    const meta = dialog.querySelector('.evidence-meta');
    const content = dialog.querySelector('.evidence-content');
    heading.textContent = '原文';
    meta.textContent = '';
    content.replaceChildren(element('p', '正在读取原文…', 'evidence-status'));
    if (!dialog.open) dialog.showModal();
    try {
        const response = await fetch('/api/knowledge/source?' + request, {signal: active.abort.signal});
        const source = await response.json();
        if (!response.ok) throw new Error(source.detail || '原文暂时无法读取');
        if (selected !== active) return;
        const citation = source.citation;
        heading.textContent = citation.title || '原文';
        meta.textContent = '版本 v' + citation.version;
        content.replaceChildren();
        if (citation.excerpt) {
            content.append(element('blockquote', citation.excerpt, 'evidence-excerpt'));
        }
        if (source.pdfUrl) {
            const pdfView = element('section', undefined, 'evidence-pdf');
            content.append(pdfView);
            await showPdf(active, source, pdfView);
        }
        if (selected === active) showLines(source, content);
    } catch (error) {
        if (selected === active) content.append(element('p', error.message, 'evidence-status'));
    }
}

function showLines(source, content) {
    if (!source.lines?.length) return;
    const heading = source.fileType === 'PDF' || source.fileType === 'DOC' ? '解析文本' : '原文片段';
    content.append(element('h3', heading, 'evidence-subheading'));
    const lines = element('div', undefined, 'evidence-lines');
    source.lines.forEach((value, index) => {
        const lineNumber = source.firstLine + index;
        const line = element('div', undefined, 'evidence-line');
        if (source.citation.line === lineNumber) line.classList.add('is-match');
        line.append(element('span', String(lineNumber), 'line-number'), element('span', value || ' '));
        lines.append(line);
    });
    content.append(lines);
    const matching = lines.querySelector('.is-match');
    if (matching && !source.pdfUrl) matching.scrollIntoView({block: 'center'});
}

async function showPdf(active, source, panel) {
    const status = element('p', '正在打开 PDF…', 'evidence-status');
    panel.append(status);
    try {
        if (!pdfModule) {
            pdfModule = import(pdfAssets + 'build/pdf.mjs').catch(error => {
                pdfModule = undefined;
                throw error;
            });
        }
        const pdfjs = await pdfModule;
        if (selected !== active) return;
        pdfjs.GlobalWorkerOptions.workerSrc = pdfAssets + 'build/pdf.worker.mjs';
        active.loading = pdfjs.getDocument({
            url: source.pdfUrl,
            disableRange: true,
            isEvalSupported: false,
            cMapUrl: pdfAssets + 'cmaps/',
            cMapPacked: true,
            standardFontDataUrl: pdfAssets + 'standard_fonts/',
            wasmUrl: pdfAssets + 'wasm/'
        });
        const pdf = await active.loading.promise;
        if (selected !== active) return;
        const matched = source.page && source.page <= pdf.numPages
            ? source.page : await findPdfPage(active, pdf, source.citation.excerpt);
        if (selected !== active) return;
        const toolbar = element('div', undefined, 'evidence-pdf-toolbar');
        const previous = element('button', '上一页', 'btn btn-secondary');
        const next = element('button', '下一页', 'btn btn-secondary');
        const counter = element('span');
        counter.setAttribute('aria-live', 'polite');
        toolbar.append(previous, counter, next);
        const pageView = element('div', undefined, 'evidence-pdf-page');
        panel.prepend(toolbar);
        panel.append(pageView);
        let current = matched || 1;

        async function renderPage(number) {
            previous.disabled = next.disabled = true;
            try {
                const page = await pdf.getPage(number);
                if (selected !== active) return;
                const viewport = page.getViewport({scale: 1});
                const scale = Math.min(2, (panel.clientWidth - 4) / viewport.width, 2200 / viewport.height);
                const ratio = Math.min(window.devicePixelRatio || 1, 2);
                const fit = page.getViewport({scale});
                const canvas = element('canvas');
                canvas.width = Math.ceil(fit.width * ratio);
                canvas.height = Math.ceil(fit.height * ratio);
                canvas.style.width = Math.round(fit.width) + 'px';
                canvas.style.height = Math.round(fit.height) + 'px';
                canvas.setAttribute('aria-label', 'PDF 第 ' + number + ' 页');
                active.render = page.render({canvas, viewport: fit, transform: [ratio, 0, 0, ratio, 0, 0]});
                await active.render.promise;
                if (selected !== active) return;
                active.render = undefined;
                current = number;
                pageView.style.width = canvas.style.width;
                pageView.replaceChildren(canvas);
                if (source.page === number && source.box?.length === 4 && page.rotate === 0) {
                    const [left, top, right, bottom] = source.box;
                    const box = element('div', undefined, 'evidence-pdf-box');
                    box.style.left = left / 10 + '%';
                    box.style.top = top / 10 + '%';
                    box.style.width = (right - left) / 10 + '%';
                    box.style.height = (bottom - top) / 10 + '%';
                    pageView.append(box);
                }
                counter.textContent = '第 ' + current + ' / ' + pdf.numPages + ' 页';
                status.textContent = current === matched ? '已定位到引用所在页' : (matched ? '' : '未自动定位，可翻页核对');
            } catch (error) {
                if (selected === active) status.textContent = '这一页无法显示，可打开原始 PDF 核对。';
            } finally {
                previous.disabled = current <= 1;
                next.disabled = current >= pdf.numPages;
            }
        }
        previous.addEventListener('click', () => renderPage(current - 1));
        next.addEventListener('click', () => renderPage(current + 1));
        await renderPage(current);
    } catch (error) {
        if (selected !== active) return;
        status.replaceChildren('PDF 预览暂时不可用。');
    }
    if (selected === active) {
        const download = element('a', '打开原始 PDF', 'evidence-link');
        download.href = source.pdfUrl;
        download.target = '_blank';
        download.rel = 'noopener';
        panel.append(download);
    }
}

async function findPdfPage(active, pdf, excerpt) {
    const normalize = text => text.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, '');
    const needles = (excerpt || '').split(/\r?\n/).map(normalize)
        .filter(line => line.length >= 12).sort((a, b) => b.length - a.length)
        .slice(0, 3).map(line => line.substring(0, 32));
    if (!needles.length || pdf.numPages > 80) return null;
    let matched;
    for (let number = 1; number <= pdf.numPages && selected === active; number++) {
        const page = await pdf.getPage(number);
        const text = await page.getTextContent();
        const joined = normalize(text.items.map(item => item.str || '').join(' '));
        if (needles.some(needle => joined.includes(needle))) {
            if (matched) return null;
            matched = number;
        }
    }
    return matched;
}

document.addEventListener('click', event => {
    const link = event.target.closest('a[data-evidence-link]');
    if (!link || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    event.preventDefault();
    openEvidence(new URL(link.href).searchParams);
});
