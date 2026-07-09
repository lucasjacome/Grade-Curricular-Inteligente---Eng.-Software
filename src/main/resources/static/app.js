const API = "";
const STORAGE_KEY = "gci.concluidas.v1";

const estado = {
    curriculo: null,
    grafo: null,
    concluidas: new Set(),
    cy: null,
    cardByCode: {},
    ultimoPlano: null,
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
    carregarConcluidas();

    const [curriculo, grafo] = await Promise.all([
        fetch(`${API}/api/curriculo`).then(r => r.json()),
        fetch(`${API}/api/grafo`).then(r => r.json()),
    ]);
    estado.curriculo = curriculo;
    estado.grafo = grafo;

    document.getElementById("subtitulo").textContent =
        `${curriculo.curso} · Currículo ${curriculo.codigoCurriculo} · ${curriculo.instituicao}`;

    renderChecklist();
    renderGrafo();
    ligarEventos();
    atualizarProgresso();
}

function ligarEventos() {
    document.getElementById("btnCalcular").addEventListener("click", calcular);
    document.getElementById("btnLimpar").addEventListener("click", limpar);
    document.getElementById("busca").addEventListener("input", filtrarBusca);
    document.querySelectorAll(".tab-btn").forEach(btn => {
        btn.addEventListener("click", () => trocarTab(btn.dataset.tab));
    });
}

/* ---------- Persistência ---------- */

function carregarConcluidas() {
    try {
        const raw = localStorage.getItem(STORAGE_KEY);
        if (raw) estado.concluidas = new Set(JSON.parse(raw));
    } catch { /* ignore */ }
}

function salvarConcluidas() {
    localStorage.setItem(STORAGE_KEY, JSON.stringify([...estado.concluidas]));
}

/* ---------- Abas ---------- */

function trocarTab(tab) {
    document.getElementById("tab-mapa").classList.toggle("hidden", tab !== "mapa");
    document.getElementById("tab-plano").classList.toggle("hidden", tab !== "plano");
    document.querySelectorAll(".tab-btn").forEach(b => {
        const ativo = b.dataset.tab === tab;
        b.classList.toggle("border-indigo-500", ativo);
        b.classList.toggle("text-indigo-400", ativo);
        b.classList.toggle("border-transparent", !ativo);
        b.classList.toggle("text-slate-400", !ativo);
    });
    if (tab === "mapa" && estado.cy) estado.cy.resize();
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
            if (estado.concluidas.has(d.codigo)) card.classList.add("concluida");
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
    if (estado.concluidas.has(codigo)) estado.concluidas.delete(codigo);
    else estado.concluidas.add(codigo);

    const card = estado.cardByCode[codigo];
    if (card) card.classList.toggle("concluida", estado.concluidas.has(codigo));

    salvarConcluidas();
    pintarGrafoBase();
    atualizarProgresso();
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
    if (estado.concluidas.has(n.id())) return "concluida";
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
            if (estado.concluidas.has(id)) {
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
        };
        const plano = await fetch(`${API}/api/plano`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(body),
        }).then(r => r.json());
        estado.ultimoPlano = plano;
        renderPlano(plano);
        trocarTab("plano");
    } catch (e) {
        alert("Erro ao calcular o plano: " + e.message);
    } finally {
        btn.disabled = false;
        btn.textContent = "Calcular rota ótima";
    }
}

function renderPlano(plano) {
    document.getElementById("planoVazio").classList.add("hidden");
    document.getElementById("planoConteudo").classList.remove("hidden");

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

    const avisos = document.getElementById("avisos");
    avisos.innerHTML = (plano.avisos && plano.avisos.length)
        ? plano.avisos.map(a => `<div class="text-xs text-amber-300 bg-amber-500/10 border border-amber-500/30 rounded-lg px-3 py-2">⚠ ${a}</div>`).join("")
        : "";

    renderGrade((plano.periodos && plano.periodos[0]) ? plano.periodos[0].disciplinas : []);
    pintarGrafoPorPlano(mapaPeriodoDoNo, total);
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

function renderGrade(disciplinas) {
    const cont = document.getElementById("gradeSemanal");
    const comHorario = (disciplinas || []).filter(d => d.turma && d.horarios && d.horarios.length);
    if (!comHorario.length) { cont.innerHTML = ""; return; }

    const cor = {};
    comHorario.forEach((d, i) => { cor[d.codigo] = GRADE_CORES[i % GRADE_CORES.length]; });

    // ocupacao[dia][slot] = disciplina
    const ocup = {};
    comHorario.forEach(d => {
        d.horarios.forEach(h => {
            const ini = paraMin(h.inicio), fim = paraMin(h.fim);
            GRADE_SLOTS.forEach(s => {
                const sm = paraMin(s);
                if (sm >= ini && sm < fim) {
                    (ocup[h.dia] = ocup[h.dia] || {})[s] = d;
                }
            });
        });
    });

    let html = `<h4 class="font-bold text-base text-white mb-3">Grade do próximo semestre (1º período)</h4>
        <div class="overflow-x-auto custom-scroll"><table class="w-full border-collapse text-sm table-fixed">
        <thead><tr><th class="p-2 text-slate-500 font-medium w-20"></th>`;
    GRADE_DIAS.forEach(([, lbl]) => {
        html += `<th class="p-2 text-slate-300 font-semibold border-b border-slate-700 text-sm">${lbl}</th>`;
    });
    html += `</tr></thead><tbody>`;

    GRADE_SLOTS.forEach(s => {
        const temAlgo = GRADE_DIAS.some(([dia]) => ocup[dia] && ocup[dia][s]);
        if (!temAlgo) return; // esconde faixas totalmente vazias
        html += `<tr class="h-16"><td class="p-2 text-right text-slate-500 pr-3 align-top text-xs font-medium">${s}</td>`;
        GRADE_DIAS.forEach(([dia]) => {
            const d = ocup[dia] && ocup[dia][s];
            if (d) {
                html += `<td class="p-1 border border-slate-800 align-top">
                    <div class="rounded-lg px-2 py-1.5 text-white leading-snug h-full flex items-center text-xs font-medium"
                         style="background:${cor[d.codigo]}dd"
                         title="${d.nome} · Turma ${d.turma}">${d.nome}</div></td>`;
            } else {
                html += `<td class="border border-slate-800/60"></td>`;
            }
        });
        html += `</tr>`;
    });
    html += `</tbody></table></div>`;
    cont.innerHTML = html;
}
