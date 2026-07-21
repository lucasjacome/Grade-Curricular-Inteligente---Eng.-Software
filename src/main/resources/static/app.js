const API = "";
const STORAGE_KEY_PREFIX = "gci.concluidas.v1.";
const STORAGE_EXCLUIR_PREFIX = "gci.excluidas.v1.";
const STORAGE_CURRICULO = "gci.curriculo.codigo";

function formatarBRL(v) {
    return (v || 0).toLocaleString("pt-BR", { style: "currency", currency: "BRL" });
}

function custosConfig() {
    return estado.curriculo?.custos ?? null;
}

function disciplinaParaCusto(item) {
    if (!item) return null;
    return disciplinaDe(item.codigo) || item;
}

function chCobranca(d) {
    const full = disciplinaParaCusto(d);
    if (!full) return 0;
    return full.cargaHorariaCobranca ?? full.cargaHoraria ?? 0;
}

function valorHoraMensal(codigo) {
    const c = custosConfig();
    if (!c) return 3.4403333;
    return c.tarifasPorCodigo?.[codigo] ?? c.valorHoraMensalPadrao;
}

/** Mensalidade que a disciplina agrega (parcelas 2–6 do SGA). */
function custoMensalDisciplina(d) {
    const full = disciplinaParaCusto(d);
    if (!full) return 0;
    return chCobranca(full) * valorHoraMensal(full.codigo);
}

function mensalidadeSemestre(itens) {
    return (itens || []).reduce((acc, item) => acc + custoMensalDisciplina(item), 0);
}

function totalSemestre(itens) {
    const c = custosConfig();
    const matricula = c?.matricula ?? 1892;
    const parcelas = c?.parcelasMensais ?? 5;
    return matricula + parcelas * mensalidadeSemestre(itens);
}

function textoChCobranca(d) {
    const full = disciplinaParaCusto(d);
    if (!full) return "";
    const ch = full.cargaHoraria;
    const cob = chCobranca(full);
    if (cob !== ch) return `${ch}h (${cob}h no SGA)`;
    return `${ch}h`;
}

const estado = {
    curriculo: null,
    curriculos: [],
    codigoCurriculo: localStorage.getItem(STORAGE_CURRICULO) || "37203",
    grafo: null,
    concluidas: new Set(),
    excluidas: new Set(),
    cy: null,
    cardByCode: {},
    ultimoPlano: null,
    semestre: null,
};

// Paleta simples e consistente (4 categorias)
const COR = {
    concluida: { bg: "#10b981", border: "#059669" },
    gargalo:   { bg: "#f59e0b", border: "#d97706" },
    optativa:  { bg: "#8b5cf6", border: "#7c3aed" },
    comum:     { bg: "#64748b", border: "#475569" },
};

document.addEventListener("DOMContentLoaded", init);

async function init() {
    if (window.cytoscapeDagre) cytoscape.use(window.cytoscapeDagre);

    estado.curriculos = await fetch(`${API}/api/curriculos`).then(r => r.json());
    popularSeletorCurriculo();
    await carregarCurriculoAtivo();
    ligarEventos();
}

function popularSeletorCurriculo() {
    const sel = document.getElementById("selCurriculo");
    sel.innerHTML = "";
    const existe = estado.curriculos.some(c => c.codigoCurriculo === estado.codigoCurriculo);
    if (!existe && estado.curriculos.length) {
        estado.codigoCurriculo = estado.curriculos[0].codigoCurriculo;
    }
    estado.curriculos.forEach(c => {
        const opt = document.createElement("option");
        opt.value = c.codigoCurriculo;
        opt.textContent = `${c.rotulo} · ${c.codigoCurriculo}`;
        if (c.codigoCurriculo === estado.codigoCurriculo) opt.selected = true;
        sel.appendChild(opt);
    });
}

async function carregarCurriculoAtivo() {
    const codigo = estado.codigoCurriculo;
    localStorage.setItem(STORAGE_CURRICULO, codigo);
    carregarConcluidas();
    carregarExcluidas();

    const [curriculo, grafo] = await Promise.all([
        fetch(`${API}/api/curriculo?codigo=${encodeURIComponent(codigo)}`).then(r => r.json()),
        fetch(`${API}/api/grafo?codigo=${encodeURIComponent(codigo)}`).then(r => r.json()),
    ]);
    estado.curriculo = curriculo;
    estado.grafo = grafo;
    estado.ultimoPlano = null;
    estado.semestre = null;

    const rotulo = curriculo.rotulo || (`Currículo ${curriculo.codigoCurriculo}`);
    document.getElementById("subtitulo").textContent =
        `${curriculo.curso} · ${rotulo} · ${curriculo.instituicao}`;

    document.getElementById("planoVazio")?.classList.remove("hidden");
    document.getElementById("planoConteudo")?.classList.add("hidden");
    document.getElementById("resumo").innerHTML = "";
    document.getElementById("periodos").innerHTML = "";
    document.getElementById("avisos").innerHTML = "";
    document.getElementById("gradeSemanal").innerHTML = "";

    renderChecklist();
    renderGrafo();
    renderConsulta();
    renderPainelExcluir();
    atualizarProgresso();
}

async function trocarCurriculo(codigo) {
    if (!codigo || codigo === estado.codigoCurriculo) return;
    estado.codigoCurriculo = codigo;
    const sel = document.getElementById("selCurriculo");
    if (sel) sel.value = codigo;
    await carregarCurriculoAtivo();
}

function ligarEventos() {
    document.getElementById("btnCalcular").addEventListener("click", calcular);
    document.getElementById("btnProximoSemestre").addEventListener("click", calcularProximoSemestre);
    document.getElementById("btnLimpar").addEventListener("click", limpar);
    document.getElementById("busca").addEventListener("input", filtrarBusca);
    document.getElementById("buscaConsulta").addEventListener("input", filtrarConsulta);
    document.getElementById("selCurriculo").addEventListener("change", e => trocarCurriculo(e.target.value));
    document.getElementById("btnFiltroExcluir").addEventListener("click", e => {
        e.stopPropagation();
        document.getElementById("painelExcluir").classList.toggle("hidden");
        renderPainelExcluir();
    });
    document.getElementById("painelExcluir").addEventListener("click", e => e.stopPropagation());
    document.getElementById("buscaExcluir").addEventListener("input", renderPainelExcluir);
    document.getElementById("btnLimparExcluidas").addEventListener("click", e => {
        e.stopPropagation();
        estado.excluidas.clear();
        salvarExcluidas();
        renderPainelExcluir();
    });
    document.addEventListener("click", () => {
        const painel = document.getElementById("painelExcluir");
        if (painel && !painel.classList.contains("hidden")) {
            painel.classList.add("hidden");
        }
    });
    document.querySelectorAll(".tab-btn").forEach(btn => {
        btn.addEventListener("click", () => trocarTab(btn.dataset.tab));
    });
    document.getElementById("modalBackdrop").addEventListener("click", fecharModal);
    document.addEventListener("keydown", e => {
        if (e.key === "Escape") fecharModal();
    });
}

/* ---------- Persistência (por currículo) ---------- */

function storageKeyConcluidas() {
    return STORAGE_KEY_PREFIX + (estado.codigoCurriculo || "37203");
}

function storageKeyExcluidas() {
    return STORAGE_EXCLUIR_PREFIX + (estado.codigoCurriculo || "37203");
}

function carregarConcluidas() {
    try {
        let raw = localStorage.getItem(storageKeyConcluidas());
        // Migração: chave antiga única → currículo 37203
        if (!raw && estado.codigoCurriculo === "37203") {
            raw = localStorage.getItem("gci.concluidas.v1");
        }
        estado.concluidas = raw ? new Set(JSON.parse(raw).map(String)) : new Set();
    } catch {
        estado.concluidas = new Set();
    }
}

function carregarExcluidas() {
    try {
        const raw = localStorage.getItem(storageKeyExcluidas());
        const lista = raw ? JSON.parse(raw).map(String) : [];
        // Mantém só códigos que existem no currículo atual (depois que ele carregar, refiltra).
        estado.excluidas = new Set(lista);
    } catch {
        estado.excluidas = new Set();
    }
}

function estaConcluida(codigo) {
    return estado.concluidas.has(String(codigo));
}

function salvarConcluidas() {
    localStorage.setItem(storageKeyConcluidas(), JSON.stringify([...estado.concluidas]));
}

function salvarExcluidas() {
    localStorage.setItem(storageKeyExcluidas(), JSON.stringify([...estado.excluidas]));
}

function adicionarExclusao(codigo) {
    codigo = String(codigo);
    if (!codigo || estaConcluida(codigo)) return;
    estado.excluidas.add(codigo);
    salvarExcluidas();
    renderPainelExcluir();
}

function removerExclusao(codigo) {
    estado.excluidas.delete(String(codigo));
    salvarExcluidas();
    renderPainelExcluir();
}

function renderPainelExcluir() {
    if (!estado.curriculo) return;

    // Remove códigos que não existem mais / já concluídos
    for (const c of [...estado.excluidas]) {
        const d = disciplinaDe(c);
        if (!d || estaConcluida(c)) estado.excluidas.delete(c);
    }
    salvarExcluidas();

    const badge = document.getElementById("badgeExcluidas");
    const n = estado.excluidas.size;
    if (n > 0) {
        badge.textContent = String(n);
        badge.classList.remove("hidden");
    } else {
        badge.classList.add("hidden");
    }

    const termo = (document.getElementById("buscaExcluir")?.value || "").trim().toLowerCase();
    const lista = document.getElementById("listaExcluirCandidatas");
    lista.innerHTML = "";

    const candidatas = estado.curriculo.disciplinas
        .filter(d => !estaConcluida(d.codigo) && !estado.excluidas.has(d.codigo))
        .filter(d => !termo || (d.nome + " " + d.codigo).toLowerCase().includes(termo))
        .slice(0, 40);

    if (!candidatas.length) {
        lista.innerHTML = `<p class="text-[12px] text-slate-500 px-2 py-3">Nenhuma disciplina disponível para excluir.</p>`;
    } else {
        candidatas.forEach(d => {
            const btn = document.createElement("button");
            btn.type = "button";
            btn.className = "w-full text-left rounded-lg px-2.5 py-2 hover:bg-slate-800 border border-transparent hover:border-slate-700 transition";
            btn.innerHTML = `
                <div class="text-[12px] font-medium text-slate-200 leading-tight">${d.nome}</div>
                <div class="text-[10px] text-slate-500 mt-0.5">${d.codigo} · ${d.periodoSugerido}º · ${d.cargaHoraria}h</div>`;
            btn.addEventListener("click", () => adicionarExclusao(d.codigo));
            lista.appendChild(btn);
        });
    }

    const chips = document.getElementById("chipsExcluidas");
    chips.innerHTML = "";
    if (!estado.excluidas.size) {
        chips.innerHTML = `<span class="text-[11px] text-slate-500 italic">Nenhuma matéria filtrada.</span>`;
        return;
    }
    [...estado.excluidas].forEach(codigo => {
        const d = disciplinaDe(codigo);
        const chip = document.createElement("button");
        chip.type = "button";
        chip.title = "Remover do filtro";
        chip.className = "inline-flex items-center gap-1 max-w-full text-[11px] font-medium text-rose-200 bg-rose-500/15 border border-rose-500/30 rounded-full pl-2.5 pr-1.5 py-1 hover:bg-rose-500/25 transition";
        chip.innerHTML = `<span class="truncate">${d?.nome || codigo}</span><span class="shrink-0 text-rose-300/80">✕</span>`;
        chip.addEventListener("click", () => removerExclusao(codigo));
        chips.appendChild(chip);
    });
}

/* ---------- Abas ---------- */

function trocarTab(tab) {
    ["mapa", "plano", "consulta"].forEach(t => {
        const el = document.getElementById("tab-" + t);
        if (el) el.classList.toggle("hidden", t !== tab);
    });
    document.querySelectorAll(".tab-btn").forEach(b => {
        const ativo = b.dataset.tab === tab;
        b.classList.toggle("border-indigo-500", ativo);
        b.classList.toggle("text-indigo-400", ativo);
        b.classList.toggle("border-transparent", !ativo);
        b.classList.toggle("text-slate-400", !ativo);
    });
    if (tab === "mapa" && estado.cy) estado.cy.resize();
    if (tab === "consulta") atualizarConsulta();
}

/* ---------- Sidebar ---------- */

function renderChecklist() {
    const container = document.getElementById("listaDisciplinas");
    container.innerHTML = "";
    estado.cardByCode = {};

    const grupos = {};
    for (const d of estado.curriculo.disciplinas) {
        (grupos[d.periodoSugerido] ??= []).push(d);
    }

    Object.keys(grupos).sort((a, b) => a - b).forEach(p => {
        const grupo = document.createElement("div");
        grupo.innerHTML = `<h3 class="text-[11px] font-bold uppercase tracking-wider text-slate-500 mb-2">${p}º período sugerido</h3>`;
        const lista = document.createElement("div");
        lista.className = "space-y-1.5";

        grupos[p].forEach(d => {
            const card = document.createElement("button");
            card.type = "button";
            card.className = "disc-card";
            if (estaConcluida(d.codigo)) card.classList.add("concluida");
            card.dataset.cod = d.codigo;
            card.dataset.busca = (d.nome + " " + d.codigo).toLowerCase();
            card.innerHTML = `
                <span class="check">✓</span>
                <span class="flex-1 min-w-0">
                    <span class="nome block text-[13px] font-medium leading-tight truncate">${d.nome}</span>
                    <span class="block text-[10px] text-slate-500 tabular-nums">${d.codigo} · ${d.cargaHoraria}h</span>
                </span>
                ${d.optativa ? '<span class="text-[9px] bg-violet-500/20 text-violet-300 px-1.5 py-0.5 rounded-full">opt</span>' : ""}`;
            card.addEventListener("click", () => toggleConcluida(d.codigo));
            estado.cardByCode[d.codigo] = card;
            lista.appendChild(card);
        });
        grupo.appendChild(lista);
        container.appendChild(grupo);
    });
}

function filtrarBusca(e) {
    const termo = e.target.value.trim().toLowerCase();
    Object.values(estado.cardByCode).forEach(card => {
        card.style.display = card.dataset.busca.includes(termo) ? "" : "none";
    });
}

/* ---------- Seleção sincronizada ---------- */

function toggleConcluida(codigo) {
    codigo = String(codigo);
    if (estaConcluida(codigo)) estado.concluidas.delete(codigo);
    else estado.concluidas.add(codigo);

    const card = estado.cardByCode[codigo];
    if (card) card.classList.toggle("concluida", estaConcluida(codigo));

    salvarConcluidas();
    pintarGrafoBase();
    atualizarProgresso();
    atualizarConsulta();
    renderPainelExcluir();
}

function atualizarProgresso() {
    const total = estado.curriculo.disciplinas.length;
    const feitas = estado.concluidas.size;
    document.getElementById("contadorConcluidas").textContent = `${feitas}/${total}`;
    document.getElementById("barraProgresso").style.width = `${(feitas / total) * 100}%`;
}

function limpar() {
    estado.concluidas.clear();
    Object.values(estado.cardByCode).forEach(c => c.classList.remove("concluida"));
    salvarConcluidas();
    pintarGrafoBase();
    atualizarProgresso();
    atualizarConsulta();
    renderPainelExcluir();
}

/* ---------- Grafo ---------- */

function renderGrafo() {
    const elements = [];
    for (const n of estado.grafo.nos) {
        elements.push({
            data: {
                id: n.codigo,
                label: n.nome,
                destrava: n.destrava,
                optativa: n.optativa,
                periodo: n.periodoSugerido,
            },
        });
    }
    for (const a of estado.grafo.arestas) {
        elements.push({
            data: { id: `${a.origem}->${a.destino}-${a.tipo}`, source: a.origem, target: a.destino, tipo: a.tipo },
        });
    }

    estado.cy = cytoscape({
        container: document.getElementById("cy"),
        elements,
        minZoom: 0.2,
        maxZoom: 2.5,
        wheelSensitivity: 0.25,
        autounselectify: true,
        style: [
            {
                selector: "node",
                style: {
                    "label": "data(label)",
                    "text-wrap": "wrap",
                    "text-max-width": "140px",
                    "font-size": "9px",
                    "font-weight": 600,
                    "text-valign": "center",
                    "text-halign": "center",
                    "color": "#f8fafc",
                    "text-outline-width": 1.5,
                    "text-outline-color": "#020617",
                    "width": 150,
                    "height": 46,
                    "padding": "6px",
                    "shape": "round-rectangle",
                    "background-color": COR.comum.bg,
                    "border-width": 2,
                    "border-color": COR.comum.border,
                    "transition-property": "background-color, border-color, opacity",
                    "transition-duration": "0.2s",
                },
            },
            {
                selector: "edge[tipo = 'PRE']",
                style: {
                    "width": 2,
                    "line-color": "#94a3b8",
                    "target-arrow-color": "#94a3b8",
                    "target-arrow-shape": "triangle",
                    "curve-style": "taxi",
                    "taxi-direction": "rightward",
                    "taxi-turn": "40px",
                    "taxi-turn-min-distance": "8px",
                    "arrow-scale": 0.9,
                    "opacity": 0.75,
                },
            },
            {
                selector: "edge[tipo = 'CO']",
                style: {
                    "width": 2,
                    "line-color": "#fbbf24",
                    "line-style": "dashed",
                    "target-arrow-color": "#fbbf24",
                    "target-arrow-shape": "triangle",
                    "curve-style": "bezier",
                    "control-point-step-size": 30,
                    "arrow-scale": 0.9,
                    "opacity": 0.8,
                },
            },
            { selector: ".apagado", style: { "opacity": 0.12 } },
        ],
        layout: layoutDagre(),
    });

    estado.cy.on("tap", "node", evt => toggleConcluida(evt.target.id()));
    estado.cy.on("mouseover", "node", evt => mostrarInfo(evt.target));
    estado.cy.on("mouseout", "node", () => document.getElementById("infoNo").classList.add("hidden"));

    pintarGrafoBase();
}

function layoutDagre() {
    if (window.cytoscapeDagre) {
        return { name: "dagre", rankDir: "LR", nodeSep: 18, edgeSep: 8, rankSep: 80, ranker: "network-simplex", padding: 30 };
    }
    return { name: "breadthfirst", directed: true, spacingFactor: 1.3, padding: 30 };
}

function mostrarInfo(node) {
    const d = estado.curriculo.disciplinas.find(x => x.codigo === node.id());
    if (!d) return;
    const nome = c => (estado.curriculo.disciplinas.find(x => x.codigo === c)?.nome ?? c);
    const box = document.getElementById("infoNo");
    box.innerHTML = `
        <div class="font-bold text-[13px] mb-1 text-white">${d.nome}</div>
        <div class="text-slate-400 mb-2">${d.codigo} · ${d.cargaHoraria}h${d.semipresencial ? " · semipresencial" : ""}</div>
        <div><span class="text-slate-500">Pré-requisitos:</span> ${d.preRequisitos.map(nome).join(", ") || "nenhum"}</div>
        <div><span class="text-slate-500">Co-requisitos:</span> ${d.coRequisitos.map(nome).join(", ") || "nenhum"}</div>
        <div class="mt-1 text-amber-400 font-semibold">Destrava ${node.data("destrava")} disciplina(s)</div>
        <div class="mt-1 text-[10px] text-slate-500">Clique para marcar como concluída</div>`;
    box.classList.remove("hidden");
}

function categoria(n) {
    if (estaConcluida(n.id())) return "concluida";
    if (n.data("optativa")) return "optativa";
    if (n.data("destrava") >= 3) return "gargalo";
    return "comum";
}

function pintarGrafoBase() {
    if (!estado.cy) return;
    estado.cy.batch(() => {
        estado.cy.nodes().forEach(n => {
            const c = COR[categoria(n)];
            n.removeClass("apagado");
            n.style({ "background-color": c.bg, "border-color": c.border });
        });
    });
}

// Após calcular: degradê de um único tom (índigo) por período => leitura clara da ordem.
function corPorPeriodo(idx, total) {
    const l = 68 - (idx / Math.max(1, total - 1)) * 30; // 68% (cedo) -> 38% (tarde)
    return { bg: `hsl(245, 62%, ${l}%)`, border: `hsl(245, 55%, ${Math.max(28, l - 12)}%)` };
}

function pintarGrafoPorPlano(mapaPeriodoDoNo, totalPeriodos) {
    if (!estado.cy) return;
    estado.cy.batch(() => {
        estado.cy.nodes().forEach(n => {
            const id = n.id();
            if (estaConcluida(id)) {
                n.removeClass("apagado");
                n.style({ "background-color": COR.concluida.bg, "border-color": COR.concluida.border });
            } else if (id in mapaPeriodoDoNo) {
                const c = corPorPeriodo(mapaPeriodoDoNo[id], totalPeriodos);
                n.removeClass("apagado");
                n.style({ "background-color": c.bg, "border-color": c.border });
            } else {
                n.addClass("apagado");
            }
        });
    });
}

/* ---------- Planejamento ---------- */

async function calcular() {
    const btn = document.getElementById("btnCalcular");
    btn.disabled = true;
    btn.textContent = "Calculando…";
    try {
        const body = {
            concluidas: [...estado.concluidas],
            maxDisciplinasPorPeriodo: Number(document.getElementById("maxDisc").value),
            incluirOptativas: document.getElementById("incluirOptativas").checked,
            considerarHorarios: true,
            codigoCurriculo: estado.codigoCurriculo,
        };
        const plano = await fetch(`${API}/api/plano`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(body),
        }).then(r => r.json());
        estado.ultimoPlano = plano;
        renderPlano(plano, "rota");
        trocarTab("plano");
    } catch (e) {
        alert("Erro ao calcular o plano: " + e.message);
    } finally {
        btn.disabled = false;
        btn.textContent = "Calcular rota ótima";
    }
}

async function calcularProximoSemestre() {
    const btn = document.getElementById("btnProximoSemestre");
    btn.disabled = true;
    btn.textContent = "Calculando…";
    try {
        const body = {
            concluidas: [...estado.concluidas],
            excluidas: [...estado.excluidas],
            maxDisciplinasPorPeriodo: Number(document.getElementById("maxDisc").value),
            incluirOptativas: document.getElementById("incluirOptativas").checked,
            considerarHorarios: true,
            codigoCurriculo: estado.codigoCurriculo,
        };
        const plano = await fetch(`${API}/api/plano/proximo-semestre`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(body),
        }).then(r => r.json());
        estado.ultimoPlano = plano;
        renderPlano(plano, "semestre");
        trocarTab("plano");
    } catch (e) {
        alert("Erro ao calcular o próximo semestre: " + e.message);
    } finally {
        btn.disabled = false;
        btn.textContent = "Calcular próximo semestre";
    }
}

function renderPlano(plano, modo = "rota") {
    document.getElementById("planoVazio").classList.add("hidden");
    document.getElementById("planoConteudo").classList.remove("hidden");

    if (modo === "semestre") {
        inicializarSemestre(plano);
        renderSemestre();
        return;
    }

    const selo = plano.otimoComprovado
        ? '<span class="text-emerald-400 font-semibold text-sm">✓ ótimo</span>'
        : '<span class="text-amber-400 font-semibold text-sm">≈ viável</span>';

    const metric = (valor, rotulo) => `
        <div class="bg-slate-800/60 border border-slate-700 rounded-2xl p-4">
            <div class="text-2xl font-extrabold text-indigo-400">${valor}</div>
            <div class="text-[11px] uppercase tracking-wide text-slate-500 mt-0.5">${rotulo}</div>
        </div>`;

    document.getElementById("resumo").innerHTML =
        metric(plano.totalPeriodos, "Períodos até formar") +
        metric(plano.totalDisciplinasRestantes, "Disciplinas restantes") +
        metric(plano.cargaHorariaRestante + "h", "Carga horária restante") +
        metric(selo, "Qualidade");

    const periodos = document.getElementById("periodos");
    periodos.innerHTML = "";
    const mapaPeriodoDoNo = {};
    const total = (plano.periodos || []).length;

    (plano.periodos || []).forEach((p, i) => {
        const c = corPorPeriodo(i, total);
        const col = document.createElement("div");
        col.className = "shrink-0 w-64 bg-slate-800/50 border border-slate-700 rounded-2xl p-3";
        col.style.borderTop = `4px solid ${c.bg}`;
        col.innerHTML = `
            <h4 class="font-bold text-sm text-white">${p.numero}º período</h4>
            <div class="text-[11px] text-slate-500 mb-3">${p.quantidade} disciplinas · ${p.cargaHorariaTotal}h</div>`;

        p.disciplinas.forEach(d => {
            mapaPeriodoDoNo[d.codigo] = i;
            const borda = d.optativa ? COR.optativa.bg : (d.destrava >= 3 ? COR.gargalo.bg : "transparent");
            const el = document.createElement("div");
            el.className = "rounded-xl border border-slate-700 bg-slate-900/70 p-2.5 mb-2";
            el.style.borderLeft = `4px solid ${borda}`;
            const tag = d.destrava >= 3
                ? '<span class="text-[9px] bg-amber-500/20 text-amber-300 px-1.5 py-0.5 rounded-full ml-1">gargalo</span>'
                : (d.optativa ? '<span class="text-[9px] bg-violet-500/20 text-violet-300 px-1.5 py-0.5 rounded-full ml-1">optativa</span>' : "");
            const horarioHtml = (d.turma && d.horarios && d.horarios.length)
                ? `<div class="mt-2 text-[10px] text-sky-300 bg-sky-500/10 border border-sky-500/25 rounded-lg px-2 py-1">
                     <span class="font-semibold">Turma ${d.turma}</span> · ${d.horarios.map(h => `${h.dia} ${h.inicio}–${h.fim}`).join(" · ")}
                   </div>`
                : "";
            el.innerHTML = `
                <div class="text-[13px] font-semibold leading-tight text-slate-100">${d.nome}${tag}</div>
                <div class="text-[10px] text-slate-500 mt-0.5">${d.codigo} · ${d.cargaHoraria}h${d.semipresencial ? " · semipresencial" : ""}</div>
                <div class="text-[11px] text-slate-400 mt-1.5">${d.motivo}</div>
                ${horarioHtml}`;
            col.appendChild(el);
        });
        periodos.appendChild(col);
    });

    renderAvisos(plano.avisos);
    renderGrade((plano.periodos && plano.periodos[0]) ? plano.periodos[0].disciplinas : []);
    pintarGrafoPorPlano(mapaPeriodoDoNo, total);
}

function renderAvisos(lista) {
    document.getElementById("avisos").innerHTML = (lista && lista.length)
        ? lista.map(a => `<div class="text-xs text-amber-300 bg-amber-500/10 border border-amber-500/30 rounded-lg px-3 py-2">⚠ ${a}</div>`).join("")
        : "";
}

/* ---------- Próximo semestre (troca no mesmo horário + remover/restaurar) ---------- */

// Cada "slot" guarda as opções que ocupam o mesmo horário e qual está escolhida.
function inicializarSemestre(plano) {
    const disciplinas = (plano.periodos && plano.periodos[0]) ? plano.periodos[0].disciplinas : [];
    const slots = disciplinas.map(d => {
        const original = {
            codigo: d.codigo, nome: d.nome, cargaHoraria: d.cargaHoraria,
            optativa: d.optativa, semipresencial: d.semipresencial,
            prioridade: d.prioridade, destrava: d.destrava,
            motivo: d.motivo, turma: d.turma, horarios: d.horarios,
        };
        const alternativas = (d.alternativas || []).map(a => ({
            codigo: a.codigo, nome: a.nome, cargaHoraria: a.cargaHoraria,
            optativa: a.optativa, semipresencial: a.semipresencial,
            prioridade: a.prioridade, destrava: a.destrava,
            motivo: `Alternativa no mesmo horário de ${d.nome}.`,
            turma: a.turma, horarios: a.horarios,
        }));
        return { opcoes: [original, ...alternativas], escolhido: d.codigo, removido: false };
    });
    estado.semestre = { plano, slots };
}

function slotsAtivos() {
    return (estado.semestre?.slots || []).filter(sl => !sl.removido);
}

function opcaoAtual(slot) {
    return slot.opcoes.find(o => o.codigo === slot.escolhido) || slot.opcoes[0];
}

function trocarAlternativa(slotIdx, codigo) {
    const s = estado.semestre;
    if (!s || !s.slots[slotIdx] || s.slots[slotIdx].removido) return;
    s.slots[slotIdx].escolhido = codigo;
    renderSemestre();
}

function removerDisciplinaSemestre(slotIdx) {
    const s = estado.semestre;
    if (!s || !s.slots[slotIdx]) return;
    s.slots[slotIdx].removido = true;
    renderSemestre();
}

function restaurarDisciplinaSemestre(slotIdx) {
    const s = estado.semestre;
    if (!s || !s.slots[slotIdx]) return;
    const candidato = opcaoAtual(s.slots[slotIdx]);
    const conflito = slotsAtivos().some(sl => disciplinasConflitam(opcaoAtual(sl), candidato));
    if (conflito) {
        alert("Não dá para restaurar: o horário conflita com outra disciplina que ainda está na grade.");
        return;
    }
    s.slots[slotIdx].removido = false;
    renderSemestre();
}

function disciplinasConflitam(a, b) {
    if (!a?.horarios?.length || !b?.horarios?.length) return false;
    for (const h1 of a.horarios) {
        for (const h2 of b.horarios) {
            if ((h1.dia || "").toUpperCase() !== (h2.dia || "").toUpperCase()) continue;
            if (paraMin(h1.inicio) < paraMin(h2.fim) && paraMin(h2.inicio) < paraMin(h1.fim)) return true;
        }
    }
    return false;
}

function renderSemestre() {
    const s = estado.semestre;
    if (!s) return;
    const plano = s.plano;
    const ativos = slotsAtivos();
    const atuais = ativos.map(opcaoAtual);
    const removidos = s.slots
        .map((sl, idx) => ({ sl, idx }))
        .filter(({ sl }) => sl.removido);

    const selo = plano.otimoComprovado
        ? '<span class="text-emerald-400 font-semibold text-sm">✓ ótimo</span>'
        : '<span class="text-amber-400 font-semibold text-sm">≈ viável</span>';
    const metric = (valor, rotulo) => `
        <div class="bg-slate-800/60 border border-slate-700 rounded-2xl p-4">
            <div class="text-2xl font-extrabold text-emerald-400">${valor}</div>
            <div class="text-[11px] uppercase tracking-wide text-slate-500 mt-0.5">${rotulo}</div>
        </div>`;

    const chSem = atuais.reduce((acc, o) => acc + chCobranca(o), 0);
    const gargalos = atuais.filter(o => o.destrava >= 3).length;
    const mensalidade = mensalidadeSemestre(atuais);
    const total = totalSemestre(atuais);
    const parcelas = custosConfig()?.parcelasMensais ?? 5;
    const matricula = custosConfig()?.matricula ?? 1892;
    document.getElementById("resumo").innerHTML =
        metric(atuais.length, "Disciplinas no próximo semestre") +
        metric(chSem + "h", "CH cobrada no SGA") +
        metric("~" + formatarBRL(mensalidade), `Mensalidade (${parcelas}x)`) +
        metric("~" + formatarBRL(total), "Total do semestre");

    const periodos = document.getElementById("periodos");
    periodos.innerHTML = "";
    const col = document.createElement("div");
    col.className = "shrink-0 w-80 bg-slate-800/50 border border-slate-700 rounded-2xl p-3";
    col.style.borderTop = `4px solid ${COR.concluida.bg}`;
    col.innerHTML = `
        <h4 class="font-bold text-sm text-white">Próximo semestre</h4>
        <div class="text-[11px] text-slate-500 mb-1">${atuais.length} disciplinas · ${chSem}h cobradas · ${gargalos} gargalo(s) · ${selo}</div>
        <div class="text-[10px] text-slate-500 mb-3">Matrícula ${formatarBRL(matricula)} + ${parcelas}× ~${formatarBRL(mensalidade)} · sem bolsas/taxas.<br>Troque no seletor (mesmo horário) ou remova o que não quiser.</div>`;

    if (!ativos.length) {
        const vazio = document.createElement("div");
        vazio.className = "rounded-xl border border-dashed border-slate-600 bg-slate-900/40 p-3 text-[12px] text-slate-400 mb-2";
        vazio.textContent = "Nenhuma disciplina na grade. Restaure alguma abaixo ou recalcule o semestre.";
        col.appendChild(vazio);
    }

    ativos.forEach((slot) => {
        const idx = s.slots.indexOf(slot);
        const d = opcaoAtual(slot);
        const borda = d.optativa ? COR.optativa.bg : (d.destrava >= 3 ? COR.gargalo.bg : "transparent");
        const el = document.createElement("div");
        el.className = "rounded-xl border border-slate-700 bg-slate-900/70 p-2.5 mb-2";
        el.id = `slot-sem-${idx}`;
        el.style.borderLeft = `4px solid ${borda}`;
        const tag = d.destrava >= 3
            ? '<span class="text-[9px] bg-amber-500/20 text-amber-300 px-1.5 py-0.5 rounded-full ml-1">gargalo</span>'
            : (d.optativa ? '<span class="text-[9px] bg-violet-500/20 text-violet-300 px-1.5 py-0.5 rounded-full ml-1">optativa</span>' : "");
        const horarioHtml = (d.turma && d.horarios && d.horarios.length)
            ? `<div class="mt-2 text-[10px] text-sky-300 bg-sky-500/10 border border-sky-500/25 rounded-lg px-2 py-1">
                 <span class="font-semibold">Turma ${d.turma}</span> · ${d.horarios.map(h => `${h.dia} ${h.inicio}–${h.fim}`).join(" · ")}
               </div>`
            : "";

        let seletorHtml = "";
        if (slot.opcoes.length > 1) {
            const options = slot.opcoes.map(o => {
                const marca = o.destrava >= 3 ? " · gargalo" : (o.optativa ? " · optativa" : "");
                const sel = o.codigo === slot.escolhido ? " selected" : "";
                return `<option value="${o.codigo}"${sel}>${o.nome} (destrava ${o.destrava}${marca})</option>`;
            }).join("");
            seletorHtml = `
                <div class="mt-2">
                    <label class="block text-[9px] uppercase tracking-wide text-emerald-400/80 font-semibold mb-1">
                        Escolher neste horário — ${slot.opcoes.length} opções
                    </label>
                    <select onchange="trocarAlternativa(${idx}, this.value)"
                            class="w-full text-[12px] rounded-lg bg-slate-800 border border-emerald-600/40 text-slate-100 px-2 py-1.5 focus:ring-2 focus:ring-emerald-500 outline-none cursor-pointer">
                        ${options}
                    </select>
                </div>`;
        } else {
            seletorHtml = `<p class="mt-2 text-[10px] text-slate-500 italic">Sem outra disciplina no mesmo horário.</p>`;
        }

        el.innerHTML = `
            <div class="flex items-start justify-between gap-2">
                <div class="min-w-0 flex-1">
                    <div class="text-[13px] font-semibold leading-tight text-slate-100">${d.nome}${tag}</div>
                    <div class="text-[10px] text-slate-500 mt-0.5">${d.codigo} · ${textoChCobranca(d)}${d.semipresencial ? " · semipresencial" : ""} · <span class="text-emerald-400 font-semibold">~${formatarBRL(custoMensalDisciplina(d))}/mês</span></div>
                </div>
                <button type="button" onclick="removerDisciplinaSemestre(${idx})"
                        title="Remover desta grade"
                        class="shrink-0 text-[11px] font-semibold text-rose-300 hover:text-rose-100 bg-rose-500/10 hover:bg-rose-500/20 border border-rose-500/30 rounded-lg px-2 py-1 transition">
                    Remover
                </button>
            </div>
            <div class="text-[11px] text-slate-400 mt-1.5">${d.motivo}</div>
            ${horarioHtml}
            ${seletorHtml}`;
        col.appendChild(el);
    });

    if (removidos.length) {
        const bloco = document.createElement("div");
        bloco.className = "mt-3 pt-3 border-t border-slate-700";
        bloco.innerHTML = `<h5 class="text-[11px] font-bold uppercase tracking-wide text-slate-400 mb-2">Removidas (${removidos.length})</h5>`;
        removidos.forEach(({ sl, idx }) => {
            const d = opcaoAtual(sl);
            const item = document.createElement("div");
            item.className = "rounded-lg border border-slate-700/80 bg-slate-900/40 px-2.5 py-2 mb-1.5 flex items-center justify-between gap-2 opacity-80";
            item.innerHTML = `
                <div class="min-w-0">
                    <div class="text-[12px] text-slate-300 truncate">${d.nome}</div>
                    <div class="text-[10px] text-slate-500">${d.codigo} · ${textoChCobranca(d)}</div>
                </div>
                <button type="button" onclick="restaurarDisciplinaSemestre(${idx})"
                        class="shrink-0 text-[11px] font-semibold text-sky-300 hover:text-sky-100 bg-sky-500/10 hover:bg-sky-500/20 border border-sky-500/30 rounded-lg px-2 py-1 transition">
                    Restaurar
                </button>`;
            bloco.appendChild(item);
        });
        col.appendChild(bloco);
    }

    periodos.appendChild(col);

    const avisosSemestre = (plano.avisos || []).slice();
    const totalTrocaveis = ativos.filter(sl => sl.opcoes.length > 1).length;
    if (totalTrocaveis > 0) {
        avisosSemestre.unshift(`${totalTrocaveis} horário(s) têm outras matérias possíveis — use o seletor "Escolher neste horário".`);
    }
    if (removidos.length > 0) {
        avisosSemestre.unshift(`${removidos.length} disciplina(s) removida(s). Custo e grade já foram recalculados; use Restaurar se mudar de ideia.`);
    }
    renderAvisos(avisosSemestre);

    renderGrade(atuais, { interativo: true });

    const mapaPeriodoDoNo = {};
    atuais.forEach(o => { mapaPeriodoDoNo[o.codigo] = 0; });
    pintarGrafoPorPlano(mapaPeriodoDoNo, 1);
}

const GRADE_DIAS = [
    ["SEG", "Segunda"], ["TER", "Terça"], ["QUA", "Quarta"], ["QUI", "Quinta"], ["SEX", "Sexta"]
];
const GRADE_SLOTS = [
    "07:00", "07:50", "08:50", "09:40", "10:40", "11:30", "12:20",
    "13:30", "14:20", "15:20", "16:10", "17:10", "18:00",
    "19:00", "19:50", "20:50", "21:40"
];
const GRADE_CORES = [
    "#6366f1", "#0ea5e9", "#10b981", "#f59e0b", "#ec4899", "#8b5cf6",
    "#14b8a6", "#ef4444", "#84cc16", "#f97316"
];

function paraMin(hhmm) {
    const [h, m] = hhmm.split(":").map(Number);
    return h * 60 + m;
}

function renderGrade(disciplinas, opts = {}) {
    const cont = document.getElementById("gradeSemanal");
    const interativo = !!opts.interativo && estado.semestre;
    const comHorario = (disciplinas || []).filter(d => d.turma && d.horarios && d.horarios.length);
    if (!comHorario.length) {
        cont.innerHTML = interativo
            ? `<h4 class="font-bold text-base text-white mb-3">Grade do próximo semestre (1º período)</h4>
               <p class="text-sm text-slate-500">Grade vazia — remova menos disciplinas ou restaure alguma.</p>`
            : "";
        return;
    }

    const cor = {};
    const slotPorCodigo = {};
    if (interativo) {
        slotsAtivos().forEach(sl => {
            const d = opcaoAtual(sl);
            if (d) slotPorCodigo[d.codigo] = estado.semestre.slots.indexOf(sl);
        });
    }
    comHorario.forEach((d, i) => { cor[d.codigo] = GRADE_CORES[i % GRADE_CORES.length]; });

    // ocupacao[dia][slot] = disciplina
    const ocup = {};
    comHorario.forEach(d => {
        d.horarios.forEach(h => {
            const dia = (h.dia || "").toUpperCase();
            const ini = paraMin(h.inicio), fim = paraMin(h.fim);
            GRADE_SLOTS.forEach(s => {
                const sm = paraMin(s);
                if (sm >= ini && sm < fim) {
                    (ocup[dia] = ocup[dia] || {})[s] = d;
                }
            });
        });
    });

    const slotsVisiveis = GRADE_SLOTS.filter(s =>
        GRADE_DIAS.some(([dia]) => ocup[dia] && ocup[dia][s]));

    let html = `<h4 class="font-bold text-base text-white mb-3">Grade do próximo semestre (1º período)</h4>
        ${interativo ? `<p class="text-[11px] text-slate-500 mb-3">Clique em uma aula para destacar a disciplina na lista (trocar ou remover).</p>` : ""}
        <div class="grade-semanal-wrap custom-scroll">
        <div class="grade-semanal" style="--grade-rows:${slotsVisiveis.length}">`;

    html += `<div class="grade-corner"></div>`;
    GRADE_DIAS.forEach(([, lbl]) => {
        html += `<div class="grade-day">${lbl}</div>`;
    });

    slotsVisiveis.forEach(s => {
        html += `<div class="grade-time">${s}</div>`;
        GRADE_DIAS.forEach(([dia]) => {
            const d = ocup[dia] && ocup[dia][s];
            if (d) {
                const idx = slotPorCodigo[d.codigo];
                const clicavel = interativo && idx != null;
                const attrs = clicavel
                    ? `onclick="focarSlotSemestre(${idx})" role="button" tabindex="0"`
                    : "";
                html += `<div class="grade-cell${clicavel ? " grade-cell--click" : ""}">
                    <div ${attrs} class="grade-aula"
                         style="background:${cor[d.codigo]}"
                         title="${d.nome} · Turma ${d.turma}${clicavel ? " · clique para editar" : ""}">${d.nome}</div>
                </div>`;
            } else {
                html += `<div class="grade-cell grade-cell--empty"></div>`;
            }
        });
    });

    html += `</div></div>`;
    cont.innerHTML = html;
}

function focarSlotSemestre(slotIdx) {
    const el = document.getElementById(`slot-sem-${slotIdx}`);
    if (!el) return;
    el.scrollIntoView({ behavior: "smooth", block: "nearest" });
    el.classList.add("ring-2", "ring-emerald-400");
    setTimeout(() => el.classList.remove("ring-2", "ring-emerald-400"), 1200);
}

/* ---------- Consultar disciplina ---------- */

function disciplinaDe(codigo) {
    return estado.curriculo.disciplinas.find(x => x.codigo === codigo);
}

function nomeDe(codigo) {
    return disciplinaDe(codigo)?.nome ?? codigo;
}

// Quantas disciplinas essa destrava (direta + indiretamente), vindo do grafo.
function destravaDe(codigo) {
    const n = (estado.grafo?.nos || []).find(x => x.codigo === codigo);
    return n ? n.destrava : 0;
}

// Carga horária acumulada = soma da CH das disciplinas já concluídas.
function chAcumulada() {
    let total = 0;
    for (const c of estado.concluidas) total += disciplinaDe(c)?.cargaHoraria ?? 0;
    return total;
}

// Disciplinas que têm `codigo` como pré/co-requisito (o que ela destrava diretamente).
function preRequisitoDe(codigo) {
    return estado.curriculo.disciplinas.filter(d => d.preRequisitos.includes(codigo));
}
function coRequisitoDe(codigo) {
    return estado.curriculo.disciplinas.filter(d => d.coRequisitos.includes(codigo));
}

function renderConsulta() {
    atualizarConsulta();
}

// Monta a lista apenas com disciplinas ainda não cursadas.
function atualizarConsulta() {
    const cont = document.getElementById("listaConsulta");
    const vazio = document.getElementById("consultaVazio");
    const termo = (document.getElementById("buscaConsulta")?.value || "").trim().toLowerCase();

    const restantes = estado.curriculo.disciplinas
        .filter(d => !estaConcluida(d.codigo))
        .sort((a, b) => (a.periodoSugerido - b.periodoSugerido) || a.nome.localeCompare(b.nome));

    const filtradas = termo
        ? restantes.filter(d => (d.nome + " " + d.codigo).toLowerCase().includes(termo))
        : restantes;

    document.getElementById("contadorRestantes").textContent = `${restantes.length} restantes`;
    vazio.classList.toggle("hidden", restantes.length > 0);
    cont.classList.toggle("hidden", filtradas.length === 0 && restantes.length > 0);

    cont.innerHTML = filtradas.map(d => {
        const st = situacao(d);
        return `<button type="button" data-cod="${d.codigo}"
            class="consulta-card w-full text-left rounded-xl border border-slate-700 bg-slate-800/50 hover:border-indigo-500 hover:bg-slate-800 transition px-3.5 py-2.5 flex items-center gap-3">
            <span class="w-2.5 h-2.5 rounded-full shrink-0" style="background:${st.cor}" title="${st.rotulo}"></span>
            <span class="flex-1 min-w-0">
                <span class="block text-[13px] font-semibold text-slate-100 leading-tight truncate">${d.nome}</span>
                <span class="block text-[10px] text-slate-500 tabular-nums mt-0.5">${d.codigo} · ${d.cargaHoraria}h · ${d.periodoSugerido}º período</span>
            </span>
            ${d.optativa ? '<span class="text-[9px] bg-violet-500/20 text-violet-300 px-1.5 py-0.5 rounded-full shrink-0">opt</span>' : ""}
            <span class="text-slate-600 shrink-0">›</span>
        </button>`;
    }).join("");

    cont.querySelectorAll(".consulta-card").forEach(btn => {
        btn.addEventListener("click", () => abrirModalDisciplina(btn.dataset.cod));
    });

    if (estado.modalCodigo) {
        if (estaConcluida(estado.modalCodigo)) fecharModal();
        else document.getElementById("modalConteudo").innerHTML = montarConteudoModal(estado.modalCodigo);
    }
}

function filtrarConsulta() {
    atualizarConsulta();
}

// Calcula a situação da disciplina em relação às concluídas.
function situacao(d) {
    if (estaConcluida(d.codigo)) {
        return { estado: "concluida", cor: "#10b981", rotulo: "Já concluída" };
    }
    const preFaltando = d.preRequisitos.filter(c => disciplinaDe(c) && !estaConcluida(c));
    const chOk = chAcumulada() >= (d.cargaHorariaMinima || 0);
    if (preFaltando.length === 0 && chOk) {
        return { estado: "disponivel", cor: "#6366f1", rotulo: "Disponível para cursar" };
    }
    return { estado: "bloqueada", cor: "#f59e0b", rotulo: "Bloqueada", preFaltando, chOk };
}

/* ---------- Modal de detalhes ---------- */

function abrirModalDisciplina(codigo) {
    codigo = String(codigo);
    const modal = document.getElementById("modalDisciplina");
    const panel = document.getElementById("modalPanel");
    const backdrop = document.getElementById("modalBackdrop");
    const conteudo = document.getElementById("modalConteudo");

    const jaAberto = modal.classList.contains("is-active");

    // Navegação interna: troca só o conteúdo, sem reanimar painel/backdrop.
    if (jaAberto && estado.modalCodigo && codigo !== estado.modalCodigo) {
        trocarDisciplinaNoModal(codigo);
        return;
    }
    if (jaAberto && codigo === estado.modalCodigo) return;

    if (estado.animPanel) estado.animPanel.cancel();
    if (estado.animBackdrop) estado.animBackdrop.cancel();
    if (estado.animConteudo) estado.animConteudo.cancel();

    estado.modalCodigo = codigo;
    conteudo.innerHTML = montarConteudoModal(codigo);
    limparEstilosAnimacao(conteudo);
    limparEstilosAnimacao(panel);

    modal.classList.add("is-active", "is-open");
    modal.setAttribute("aria-hidden", "false");
    panel.scrollTop = 0;

    if (typeof panel.animate !== "function") return;

    estado.animBackdrop = backdrop.animate(
        [{ opacity: 0 }, { opacity: 1 }],
        { duration: 260, easing: "ease", fill: "both" }
    );
    estado.animPanel = panel.animate(
        [
            { opacity: 0, transform: "scale(0.9) translateY(28px)" },
            { opacity: 1, transform: "scale(1) translateY(0)" },
        ],
        { duration: 360, easing: "cubic-bezier(0.16, 1, 0.3, 1)", fill: "both" }
    );
    estado.animPanel.onfinish = () => limparEstilosAnimacao(panel);
}

// Transição fluida ao clicar em pré/co-requisito dentro do pop-up.
function trocarDisciplinaNoModal(codigo) {
    const conteudo = document.getElementById("modalConteudo");
    const panel = document.getElementById("modalPanel");

    if (estado.animConteudo) {
        estado.animConteudo.cancel();
        limparEstilosAnimacao(conteudo);
    }

    estado.modalCodigo = codigo;

    if (typeof conteudo.animate !== "function") {
        conteudo.innerHTML = montarConteudoModal(codigo);
        panel.scrollTop = 0;
        return;
    }

    const saida = conteudo.animate(
        [
            { opacity: 1, transform: "translateX(0)" },
            { opacity: 0, transform: "translateX(-10px)" },
        ],
        { duration: 100, easing: "ease-in", fill: "forwards" }
    );

    saida.onfinish = () => {
        limparEstilosAnimacao(conteudo);
        conteudo.innerHTML = montarConteudoModal(codigo);
        panel.scrollTop = 0;
        estado.animConteudo = conteudo.animate(
            [
                { opacity: 0, transform: "translateX(12px)" },
                { opacity: 1, transform: "translateX(0)" },
            ],
            { duration: 160, easing: "cubic-bezier(0.16, 1, 0.3, 1)", fill: "forwards" }
        );
        estado.animConteudo.onfinish = () => limparEstilosAnimacao(conteudo);
        estado.animConteudo.oncancel = () => limparEstilosAnimacao(conteudo);
    };
    saida.oncancel = () => limparEstilosAnimacao(conteudo);
}

function limparEstilosAnimacao(el) {
    if (!el) return;
    el.style.removeProperty("opacity");
    el.style.removeProperty("transform");
    el.style.removeProperty("filter");
}

function fecharModal() {
    const modal = document.getElementById("modalDisciplina");
    const panel = document.getElementById("modalPanel");
    const backdrop = document.getElementById("modalBackdrop");
    if (!modal.classList.contains("is-active")) return;

    modal.setAttribute("aria-hidden", "true");

    const finalizar = () => {
        modal.classList.remove("is-active", "is-open");
        estado.modalCodigo = null;
        estado.animPanel = null;
        estado.animBackdrop = null;
        estado.animConteudo = null;
        limparEstilosAnimacao(panel);
        limparEstilosAnimacao(document.getElementById("modalConteudo"));
    };

    if (typeof panel.animate !== "function") {
        finalizar();
        return;
    }

    if (estado.animPanel) estado.animPanel.cancel();
    if (estado.animBackdrop) estado.animBackdrop.cancel();
    if (estado.animConteudo) estado.animConteudo.cancel();

    backdrop.animate([{ opacity: 1 }, { opacity: 0 }], { duration: 200, easing: "ease", fill: "both" });
    const saida = panel.animate(
        [
            { opacity: 1, transform: "scale(1) translateY(0)" },
            { opacity: 0, transform: "scale(0.95) translateY(12px)" },
        ],
        { duration: 220, easing: "ease", fill: "both" }
    );
    estado.animPanel = saida;
    saida.onfinish = finalizar;
    saida.oncancel = () => { /* substituída por nova abertura */ };
}

// Linha clicável para um requisito (pré ou co), mostrando se já foi concluído.
function linhaRequisito(codigo) {
    const existe = disciplinaDe(codigo);
    const feito = estaConcluida(codigo);
    const clique = existe ? `onclick="abrirModalDisciplina('${codigo}')"` : "";
    const cls = existe ? "hover:border-indigo-500 cursor-pointer" : "opacity-70 cursor-default";
    const marca = feito
        ? '<span class="w-4 h-4 rounded-full grid place-items-center text-[10px] bg-emerald-500 text-white shrink-0">✓</span>'
        : '<span class="w-4 h-4 rounded-full grid place-items-center text-[10px] bg-slate-700 text-slate-400 shrink-0">•</span>';
    return `<button type="button" ${clique}
        class="w-full flex items-center gap-2 text-left rounded-lg px-2.5 py-2 border transition ${feito ? 'border-emerald-600/40 bg-emerald-500/10' : 'border-slate-700 bg-slate-800/40'} ${cls}">
        ${marca}
        <span class="flex-1 text-[13px] ${feito ? 'text-emerald-200' : 'text-slate-200'}">${nomeDe(codigo)}</span>
        <span class="text-[10px] text-slate-500 tabular-nums">${codigo}</span>
    </button>`;
}

function montarConteudoModal(codigo) {
    const d = disciplinaDe(codigo);
    if (!d) return "";

    const st = situacao(d);
    const preReais = d.preRequisitos.filter(c => disciplinaDe(c));
    const coReais = d.coRequisitos.filter(c => disciplinaDe(c));
    const destravaDireto = preRequisitoDe(codigo);
    const coDe = coRequisitoDe(codigo);
    const destravaTotal = destravaDe(codigo);
    const chMin = d.cargaHorariaMinima || 0;
    const chAcum = chAcumulada();

    const badge = (txt, cor) =>
        `<span class="text-[10px] font-semibold px-2 py-0.5 rounded-full bg-${cor}-500/20 text-${cor}-300">${txt}</span>`;

    const badges = [
        d.optativa ? badge("optativa", "violet") : "",
        d.semipresencial ? badge("semipresencial", "sky") : "",
        destravaTotal >= 3 ? badge("gargalo", "amber") : "",
    ].join(" ");

    // Banner de situação
    let banner;
    if (st.estado === "concluida") {
        banner = `<div class="rounded-xl border border-emerald-600/40 bg-emerald-500/10 px-4 py-3 text-emerald-200 text-sm font-semibold flex items-center gap-2">
            ✓ Você já concluiu esta disciplina.
        </div>`;
    } else if (st.estado === "disponivel") {
        banner = `<div class="rounded-xl border border-indigo-500/40 bg-indigo-500/10 px-4 py-3 text-indigo-200 text-sm font-semibold flex items-center gap-2">
            ▶ Disponível para cursar agora — você cumpre todos os requisitos.
        </div>`;
    } else {
        const motivos = [];
        if (st.preFaltando.length) {
            motivos.push(`Faltam ${st.preFaltando.length} pré-requisito(s): ${st.preFaltando.map(nomeDe).join(", ")}.`);
        }
        if (!st.chOk) {
            motivos.push(`Falta carga horária: você tem ${chAcum}h de ${chMin}h exigidas (faltam ${chMin - chAcum}h).`);
        }
        banner = `<div class="rounded-xl border border-amber-500/40 bg-amber-500/10 px-4 py-3 text-amber-200 text-sm">
            <div class="font-semibold mb-1">⚠ Ainda bloqueada</div>
            <ul class="list-disc list-inside space-y-0.5 text-[13px] text-amber-100/90">
                ${motivos.map(m => `<li>${m}</li>`).join("")}
            </ul>
        </div>`;
    }

    // Bloco CH mínima
    const pctCh = chMin > 0 ? Math.min(100, Math.round((chAcum / chMin) * 100)) : 100;
    const chBloco = chMin > 0 ? `
        <div>
            <div class="flex items-center justify-between text-[11px] mb-1">
                <span class="text-slate-400 font-medium">Carga horária mínima exigida</span>
                <span class="tabular-nums ${chAcum >= chMin ? 'text-emerald-400' : 'text-amber-400'} font-semibold">${chAcum}h / ${chMin}h</span>
            </div>
            <div class="h-2 rounded-full bg-slate-800 overflow-hidden">
                <div class="h-full ${chAcum >= chMin ? 'bg-emerald-500' : 'bg-amber-500'}" style="width:${pctCh}%"></div>
            </div>
        </div>` : `<p class="text-[12px] text-slate-500">Sem exigência de carga horária mínima acumulada.</p>`;

    // Bloco de custo mensal estimado (o quanto a disciplina soma na mensalidade)
    const chCob = chCobranca(d);
    const vHora = valorHoraMensal(d.codigo);
    const tarifaEspecial = custosConfig()?.tarifasPorCodigo?.[d.codigo];
    const custoBloco = `
        <div class="rounded-xl border border-emerald-600/40 bg-emerald-500/10 px-4 py-3 flex items-center justify-between gap-3">
            <div>
                <div class="text-[11px] uppercase tracking-wide text-emerald-300/80 font-semibold">Custo mensal estimado</div>
                <div class="text-[11px] text-slate-400 mt-0.5">${chCob}h × ${formatarBRL(vHora)}/h${tarifaEspecial ? " · tarifa adaptação" : ""} · fora matrícula/bolsas</div>
            </div>
            <div class="text-right whitespace-nowrap">
                <span class="text-xl font-extrabold text-emerald-400 tabular-nums">${formatarBRL(custoMensalDisciplina(d))}</span>
                <span class="text-[11px] text-emerald-300/70">/mês</span>
            </div>
        </div>`;

    const secaoLista = (titulo, subtitulo, itens, vazio) => `
        <div>
            <h4 class="text-[13px] font-bold text-white">${titulo}</h4>
            <p class="text-[11px] text-slate-500 mb-2">${subtitulo}</p>
            ${itens.length ? `<div class="space-y-1.5">${itens}</div>`
                            : `<p class="text-[12px] text-slate-500 italic">${vazio}</p>`}
        </div>`;

    return `
        <div class="sticky top-0 bg-slate-900/95 backdrop-blur border-b border-slate-800 px-5 py-4 flex items-start gap-3">
            <div class="flex-1 min-w-0">
                <div class="flex items-center gap-2 flex-wrap">
                    <h3 class="text-lg font-bold text-white leading-tight">${d.nome}</h3>
                    ${badges}
                </div>
                <p class="text-xs text-slate-400 mt-1 tabular-nums">
                    ${d.codigo} · ${textoChCobranca(d)} · ${d.periodoSugerido}º período sugerido
                </p>
            </div>
            <button type="button" onclick="fecharModal()"
                    class="shrink-0 w-8 h-8 grid place-items-center rounded-lg text-slate-400 hover:text-white hover:bg-slate-800 transition text-lg">✕</button>
        </div>

        <div class="p-5 space-y-5">
            ${banner}

            <div class="grid grid-cols-3 gap-3">
                <div class="bg-slate-800/50 border border-slate-700 rounded-xl p-3 text-center">
                    <div class="text-xl font-extrabold text-indigo-400 tabular-nums">${destravaTotal}</div>
                    <div class="text-[10px] uppercase tracking-wide text-slate-500 mt-0.5">Disciplinas que destrava</div>
                </div>
                <div class="bg-slate-800/50 border border-slate-700 rounded-xl p-3 text-center">
                    <div class="text-xl font-extrabold text-indigo-400 tabular-nums">${preReais.length}</div>
                    <div class="text-[10px] uppercase tracking-wide text-slate-500 mt-0.5">Pré-requisitos</div>
                </div>
                <div class="bg-slate-800/50 border border-slate-700 rounded-xl p-3 text-center">
                    <div class="text-xl font-extrabold text-indigo-400 tabular-nums">${coReais.length}</div>
                    <div class="text-[10px] uppercase tracking-wide text-slate-500 mt-0.5">Co-requisitos</div>
                </div>
            </div>

            ${chBloco}

            ${custoBloco}

            <div class="border-t border-slate-800 pt-4">
                <h3 class="text-sm font-bold text-white mb-3">O que você precisa para cursá-la</h3>
                <div class="grid sm:grid-cols-2 gap-4">
                    ${secaoLista("Pré-requisitos", "Precisam estar concluídos antes.",
                        preReais.map(linhaRequisito).join(""), "Nenhum pré-requisito.")}
                    ${secaoLista("Co-requisitos", "Podem ser cursados no mesmo período ou antes.",
                        coReais.map(linhaRequisito).join(""), "Nenhum co-requisito.")}
                </div>
            </div>

            <div class="border-t border-slate-800 pt-4">
                <h3 class="text-sm font-bold text-white mb-3">O que ela destrava</h3>
                <div class="grid sm:grid-cols-2 gap-4">
                    ${secaoLista("É pré-requisito de", "Disciplinas que só liberam depois desta.",
                        destravaDireto.map(x => linhaRequisito(x.codigo)).join(""), "Não é pré-requisito de nenhuma disciplina.")}
                    ${secaoLista("É co-requisito de", "Disciplinas que a exigem como co-requisito.",
                        coDe.map(x => linhaRequisito(x.codigo)).join(""), "Não é co-requisito de nenhuma disciplina.")}
                </div>
            </div>
        </div>`;
}
