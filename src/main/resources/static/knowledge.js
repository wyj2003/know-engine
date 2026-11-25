import {citationLink, openEvidence} from './knowledge-evidence.js';

const byId = id => document.getElementById(id);
const documentList = new Map();
const versionCache = new Map();
const params = new URLSearchParams(location.search);
const api = '/api/knowledge';
let schemaRequest = 0;

function element(tag, text, className) {
    const node = document.createElement(tag);
    if (text !== undefined) node.textContent = text;
    if (className) node.className = className;
    return node;
}

function status(id, text, error = false) {
    const node = byId(id);
    node.textContent = text;
    node.classList.toggle('is-error', error);
}

async function request(url, payload) {
    const options = payload === undefined ? {} : {
        method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(payload)
    };
    const response = await fetch(url, options);
    if (response.ok) return response.json();
    const error = response.headers.get('content-type')?.includes('json')
        ? await response.json() : {detail: await response.text()};
    throw new Error(error.detail || error.message || '请求失败（' + response.status + '）');
}

function options(select, values, placeholder, preferred) {
    select.replaceChildren(new Option(placeholder, ''), ...values.map(([id, title]) => new Option(title, id)));
    if (preferred && Array.from(select.options).some(choice => choice.value === String(preferred))) {
        select.value = String(preferred);
    }
}

async function loadDocuments() {
    let current = 1;
    let page;
    do {
        page = await request('/api/document/page?size=100&current=' + current);
        (page.records || []).forEach(doc => documentList.set(String(doc.docId), doc));
        current++;
    } while (current <= Number(page.pages || 1) || (page.total > documentList.size && page.records?.length === 100));
    const requested = params.get('document');
    if (/^[1-9]\d{0,18}$/.test(requested || '') && !documentList.has(requested)) {
        const doc = await request('/api/document/' + requested);
        if (doc?.docId) documentList.set(String(doc.docId), doc);
    }
}

function versions(documentId) {
    if (!versionCache.has(documentId)) {
        const pending = request('/api/document/versions/' + encodeURIComponent(documentId)).catch(error => {
            versionCache.delete(documentId);
            throw error;
        });
        versionCache.set(documentId, pending);
    }
    return versionCache.get(documentId);
}

function isTable(version) {
    return /\.(xlsx?|csv)$/i.test((version.docUrl || '').split('?')[0]);
}

function setupPicker(documentId, versionIds, config = {}) {
    const picker = byId(documentId);
    const versionPickers = versionIds.map(byId);
    const docs = Array.from(documentList.entries()).map(([id, doc]) => [id, doc.docTitle || '文档 ' + id]);
    options(picker, docs, '请选择文档', config.document);

    async function update() {
        const id = picker.value;
        versionPickers.forEach(select => options(select, [], id ? '正在读取…' : '请先选择文档'));
        if (!id) {
            config.changed?.();
            return;
        }
        try {
            let available = await versions(id);
            if (picker.value !== id) return;
            available = available.filter(version => version.convertedDocUrl && (!config.table || isTable(version)));
            const active = String(documentList.get(id)?.currentVersionId || '');
            const choices = available.map(version => {
                const current = String(version.versionId) === active ? ' · 当前' : '';
                return [String(version.versionId), 'v' + version.version + current];
            });
            const first = choices.find(([key]) => key === active)?.[0] || choices[0]?.[0];
            versionPickers.forEach((select, index) => {
                const other = choices.find(([key]) => key !== first)?.[0];
                const preferred = index === 0 ? first : (config.candidate && id === config.document ? config.candidate : other);
                options(select, choices, choices.length ? '请选择版本' : '没有可用版本', preferred);
            });
            config.changed?.();
        } catch (error) {
            status('workbenchStatus', error.message, true);
        }
    }
    picker.addEventListener('change', update);
    update();
}

function footerMessage(text) {
    return element('p', text, 'workbench-status');
}

function evidenceList(evidence) {
    if (!evidence?.length) return footerMessage('没有匹配的证据。');
    const list = element('ol', undefined, 'workbench-evidence');
    for (const item of evidence) {
        const li = element('li');
        const location = item.line ? ' · 第 ' + item.line + ' 行' : '';
        li.append(citationLink(item, item.title + ' · v' + item.version + location));
        if (item.excerpt) li.append(element('p', item.excerpt));
        list.append(li);
    }
    return list;
}

function answerCard(label, answer) {
    const card = element('div', undefined, 'review-result-card');
    card.append(element('h4', label), element('p', answer.text), evidenceList(answer.evidence));
    return card;
}

function renderReview(payload, documentId) {
    const container = document.createDocumentFragment();
    container.append(element('h3', '原文差异'));
    if (!payload.changes.length) {
        container.append(footerMessage(payload.truncated ? '已比较的范围内没有差异。' : '未发现原文差异。'));
    }
    for (const change of payload.changes) {
        const cards = element('div', undefined, 'review-grid');
        for (const [version, lines, line, style] of [
            [payload.baseline, change.removed, change.oldLine, 'removed'],
            [payload.candidate, change.added, change.newLine, 'added']
        ]) {
            const card = element('div', undefined, 'review-result-card ' + (lines.length ? style : ''));
            if (lines.length) {
                const link = citationLink({documentId, versionId: version.id, line},
                    'v' + version.label + ' · 第 ' + line + ' 行');
                card.append(link, element('pre', lines.join('\n')));
            } else {
                card.append(element('h4', 'v' + version.label), footerMessage('此处没有对应内容。'));
            }
            cards.append(card);
        }
        container.append(cards);
    }
    if (payload.truncated) container.append(footerMessage('长文档或较长的差异仅展示一部分。'));
    for (const item of payload.questions) {
        const section = element('section', undefined, 'question-comparison');
        section.append(element('h3', item.question));
        const grid = element('div', undefined, 'review-grid');
        grid.append(answerCard('v' + payload.baseline.label, item.baseline),
            answerCard('v' + payload.candidate.label, item.candidate));
        section.append(grid);
        container.append(section);
    }
    return container;
}

function bindForm(formId, statusId, targetId, loading, execute) {
    const form = byId(formId);
    const submit = form.querySelector('button[type="submit"]');
    form.addEventListener('submit', async event => {
        event.preventDefault();
        submit.disabled = true;
        byId(targetId).replaceChildren();
        status(statusId, loading);
        try {
            byId(targetId).replaceChildren(await execute());
            status(statusId, '');
        } catch (error) {
            status(statusId, error.message, true);
        } finally {
            submit.disabled = false;
        }
    });
}

async function loadSchema(keepSheet = false) {
    const sequence = ++schemaRequest;
    const sheet = keepSheet ? byId('tableSheet').value : '';
    const table = selectedTable(sheet);
    options(byId('tableSheet'), [], '');
    ['tableColumn', 'tableGroup', 'filterColumn'].forEach(id => options(byId(id), [], '请选择'));
    byId('tableResult').replaceChildren();
    byId('tableSourceRows').replaceChildren();
    byId('tableSummary').textContent = '';
    status('tableStatus', '');
    if (!table.versionId) return;
    try {
        const schema = await request(api + '/table/schema?' + tableParams(table));
        if (sequence !== schemaRequest) return;
        options(byId('tableSheet'), schema.sheets.map(name => [name, name]), '请选择工作表', schema.sheet);
        const columns = schema.columns.map(name => [name, name]);
        options(byId('tableColumn'), columns, '请选择数值列');
        options(byId('tableGroup'), columns, '不分组');
        options(byId('filterColumn'), columns, '不筛选');
        byId('tableSummary').textContent = schema.sheet + ' · ' + schema.rowCount + ' 条数据 · ' + columns.length + ' 列';
        updateTableControls();
    } catch (error) {
        if (sequence === schemaRequest) status('tableStatus', error.message, true);
    }
}

function selectedTable(sheet = byId('tableSheet').value) {
    return {documentId: byId('tableDocument').value, versionId: byId('tableVersion').value, sheet};
}

function tableParams(target) {
    const query = new URLSearchParams({documentId: target.documentId, versionId: target.versionId});
    if (target.sheet) query.set('sheet', target.sheet);
    return query;
}

function updateTableControls() {
    const operation = byId('tableOperation').value;
    const numeric = !['ROWS', 'COUNT'].includes(operation);
    byId('tableColumn').disabled = !numeric;
    byId('tableColumn').required = numeric;
    byId('tableGroup').disabled = ['ROWS', 'TOP_LOWEST', 'TOP_HIGHEST'].includes(operation);
    byId('filterValue').required = Boolean(byId('filterColumn').value);
}

const calculationLabels = {
    ROWS: '查看记录', TOP_LOWEST: '最低值排行', TOP_HIGHEST: '最高值排行',
    COUNT: '计数', SUM: '求和', AVG: '平均值', MIN: '最小值', MAX: '最大值'
};
const filterLabels = {EQ: '等于', CONTAINS: '包含', GT: '大于', GTE: '大于等于', LT: '小于', LTE: '小于等于'};

function dataTable(columns, data) {
    const scroller = element('div', undefined, 'result-table');
    const table = element('table');
    const head = table.createTHead().insertRow();
    columns.forEach(name => head.append(element('th', name)));
    const body = table.createTBody();
    for (const row of data) {
        const tr = body.insertRow();
        for (const value of row) {
            const cell = tr.insertCell();
            if (value instanceof Node) cell.append(value);
            else cell.textContent = value ?? '—';
        }
    }
    scroller.append(table);
    return scroller;
}

function renderCalculation(result) {
    const container = document.createDocumentFragment();
    const {source, query} = result;
    byId('tableSourceRows').replaceChildren();
    container.append(element('h3', source.title + ' · v' + source.version + ' · ' + source.sheet));
    const calculation = [calculationLabels[query.operation], query.column, query.groupBy && '按 ' + query.groupBy + ' 分组']
        .filter(Boolean).join(' · ');
    const filters = query.filters.map(item => item.column + ' ' + filterLabels[item.operator] + ' ' + item.value);
    container.append(footerMessage([calculation, ...filters].join(' · ') + ' · 至多 ' + query.limit + ' 条结果'));
    const rows = result.rows.map(row => {
        const provenance = element('div');
        if (row.sourceRows.length) {
            const label = row.sourceRowCount > row.sourceRows.length ? '查看前 ' + row.sourceRows.length + ' 条来源' : '查看来源行';
            const button = element('button', label, 'btn btn-xs btn-secondary');
            button.type = 'button';
            button.addEventListener('click', () => loadSourceRows(result, row.sourceRows));
            provenance.append(button);
        }
        const basis = row.numericRowCount == null ? row.sourceRowCount + ' 条' : row.numericRowCount + ' 条数值 / ' + row.sourceRowCount + ' 条匹配';
        provenance.append(footerMessage(basis + (row.sourceRows.length === 1 ? ' · #' + row.sourceRows[0] : '')));
        return [...row.values, provenance];
    });
    container.append(rows.length ? dataTable([...result.columns, '来源'], rows) : footerMessage('没有符合条件的数据。'));
    return container;
}

async function loadSourceRows(result, rows) {
    const container = byId('tableSourceRows');
    container.replaceChildren(footerMessage('正在读取来源…'));
    const query = tableParams({...result, sheet: result.source.sheet});
    rows.forEach(row => query.append('rows', row));
    try {
        const payload = await request(api + '/table/source-rows?' + query);
        container.replaceChildren(element('h3', '来源行 · ' + payload.source.sheet),
            dataTable([payload.source.sheet === 'CSV' ? '记录号' : '行号', ...payload.source.columns],
                payload.rows.map(row => [row.number, ...row.cells])));
        container.scrollIntoView({block: 'nearest'});
    } catch (error) {
        container.replaceChildren(footerMessage(error.message));
    }
}

function renderResearch(result) {
    const container = document.createDocumentFragment();
    const first = element('section', undefined, 'research-stage');
    first.append(element('h3', '第一步 · 查找线索'), evidenceList(result.initialEvidence));
    const second = element('section', undefined, 'research-stage');
    second.append(element('h3', '第二步 · 查找相关证据'));
    if (result.secondQuery) second.append(footerMessage('检索关键词 · ' + result.secondQuery));
    second.append(answerCard('回答与证据', result.answer));
    container.append(first, second);
    return container;
}

function selectPanel(id) {
    document.querySelectorAll('.workbench-tabs button').forEach(button => {
        const active = button.dataset.panel === id;
        button.setAttribute('aria-pressed', String(active));
        byId(button.dataset.panel).hidden = !active;
    });
}

async function initialize() {
    const tabs = document.querySelectorAll('.workbench-tabs button');
    tabs.forEach(button => button.addEventListener('click', () => selectPanel(button.dataset.panel)));
    if (Array.from(tabs).some(button => button.dataset.panel === params.get('tab'))) selectPanel(params.get('tab'));
    if (params.get('sourceDocument')) openEvidence(params);
    const port = location.port ? ':' + location.port : '';
    byId('mcpAddress').textContent = location.protocol + '//127.0.0.1' + port + '/mcp';
    byId('copyMcp').addEventListener('click', async () => {
        try {
            await navigator.clipboard.writeText(byId('mcpAddress').textContent);
            status('mcpStatus', '地址已复制。');
        } catch (error) {
            status('mcpStatus', '请选择地址文字复制。');
        }
    });

    bindForm('reviewForm', 'reviewStatus', 'reviewResult', '正在读取并比较版本…', async () => {
        const documentId = byId('reviewDocument').value;
        const baselineId = byId('baselineVersion').value;
        const candidateId = byId('candidateVersion').value;
        const questions = byId('reviewQuestions').value.split(/\r?\n/).map(line => line.trim()).filter(Boolean);
        return renderReview(await request(api + '/version-review', {documentId, baselineId, candidateId, questions}), documentId);
    });
    bindForm('tableQuestionForm', 'tableStatus', 'tableResult', '正在根据问题计算…', async () => {
        const target = selectedTable();
        if (!target.sheet) throw new Error('请先选择文档版本和工作表');
        return renderCalculation(await request(api + '/table/query', {...target, question: byId('tableQuestion').value}));
    });
    bindForm('tableManualForm', 'tableStatus', 'tableResult', '正在计算表格…', async () => {
        const target = selectedTable();
        if (!target.sheet) throw new Error('请先选择文档版本和工作表');
        const column = byId('filterColumn').value;
        const filters = column ? [{column, operator: byId('filterOperator').value, value: byId('filterValue').value}] : [];
        const query = {operation: byId('tableOperation').value,
            column: byId('tableColumn').disabled ? null : byId('tableColumn').value,
            groupBy: byId('tableGroup').disabled ? null : byId('tableGroup').value,
            filters, limit: Number(byId('tableLimit').value)};
        return renderCalculation(await request(api + '/table/query', {...target, query}));
    });
    bindForm('researchForm', 'researchStatus', 'researchResult', '正在查找线索和证据…', async () => {
        return renderResearch(await request(api + '/research', {
            firstDocument: byId('firstDocument').value, firstVersion: byId('firstVersion').value,
            secondDocument: byId('secondDocument').value, secondVersion: byId('secondVersion').value,
            question: byId('researchQuestion').value
        }));
    });

    ['tableOperation', 'filterColumn'].forEach(id => byId(id).addEventListener('change', updateTableControls));
    byId('tableVersion').addEventListener('change', () => loadSchema());
    byId('tableSheet').addEventListener('change', () => loadSchema(true));
    try {
        await loadDocuments();
        setupPicker('reviewDocument', ['baselineVersion', 'candidateVersion'],
            {document: params.get('document'), candidate: params.get('candidate')});
        setupPicker('tableDocument', ['tableVersion'], {table: true, changed: loadSchema});
        setupPicker('firstDocument', ['firstVersion']);
        setupPicker('secondDocument', ['secondVersion']);
        if (!documentList.size) status('workbenchStatus', '请先上传文档。');
    } catch (error) {
        status('workbenchStatus', error.message, true);
    }
}

initialize();
