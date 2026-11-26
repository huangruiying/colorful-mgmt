"use strict";
(() => {
    const byId = id => document.getElementById(id);
    const form = byId("plan-form");
    const editor = byId("editor");
    const questionPageSize = 20;
    let questionPage = 0;
    let viewOnly = false;
    const intentLabels = {RECOMMENDATION:"产品推荐", PROBLEM_SOLVING:"问题解决", COMPARISON:"产品比较", PURCHASE:"采购选型", TECHNICAL:"技术能力", AWARENESS:"品牌认知"};
    const scopeLabels = {NON_BRAND:"非品牌发现", BRAND:"品牌认知", COMPARISON:"竞品比较"};
    let planId = null, page = 0, questions = [], candidates = [], channels = [], ready = false, revision = 0, generating = false, saving = false, dirty = false;
    // 项目列表用于显示目标市场参考；地域行只用于本次生成（每行一个地域和语言），不随方案保存
    let projects = [], localeRows = [];
    const message = (id, text) => {
        const target = byId(editor.open ? "editor-" + id : id);
        for (const element of [byId(id), byId("editor-" + id)]) {
            element.textContent = ""; element.hidden = true;
        }
        target.textContent = text; target.hidden = !text;
        return target;
    };
    const error = text => {
        const target = message("plan-error", text);
        if (text) target.scrollIntoView({behavior:"smooth", block:"center"});
    };
    const normalize = text => text.normalize("NFKC").toLocaleLowerCase().replace(/\s+/g, "");
    const markDirty = () => { dirty = true; message("plan-success", ""); };
    const request = window.AdminHttp.requestJson;
    const allItems = window.AdminPagination.listAll;
    function button(text, action, className = "text-button") {
        const element = document.createElement("button");
        element.type = "button"; element.className = className; element.textContent = text;
        element.addEventListener("click", action);
        return element;
    }
    async function loadOptions() {
        const [projectItems, models] = await Promise.all([allItems("/projects"), allItems("/llm-channels")]);
        projects = projectItems; channels = models;
        form.elements.projectId.replaceChildren(new Option("请选择项目", ""));
        projects.forEach(project => form.elements.projectId.add(new Option(project.name, project.id)));
        byId("generation-channel").replaceChildren(new Option("按数据库优先级选择", ""));
        byId("test-channels").replaceChildren();
        for (const channel of channels) {
            const label = document.createElement("label");
            label.className = "channel-option";
            const checkbox = document.createElement("input");
            checkbox.type = "checkbox"; checkbox.value = channel.id; checkbox.disabled = !channel.enabled;
            checkbox.addEventListener("change", () => { markDirty(); updateCount(); });
            const copy = document.createElement("span");
            copy.textContent = channel.channelName + (channel.enabled ? "" : "（已停用）");
            const detail = document.createElement("small");
            detail.textContent = channel.modelName + " · " + channel.strategyBeanName;
            copy.append(detail); label.append(checkbox, copy); byId("test-channels").append(label);
            if (channel.enabled) byId("generation-channel").add(new Option(channel.channelName, channel.channelCode));
        }
        if (!projects.length || !channels.some(channel => channel.enabled))
            message("plan-success", "请先创建项目并启用模型渠道，再保存诊断方案。");
        ready = true; byId("new-plan").disabled = false;
    }
    async function loadList() {
        const result = await request("/diagnosis-plans?page=" + page + "&size=10&keyword=" + encodeURIComponent(byId("keyword").value.trim()));
        const rows = byId("plan-list"); rows.replaceChildren();
        if (!result.items.length) {
            const row = rows.insertRow(), cell = row.insertCell();
            cell.colSpan = 6; cell.className = "table-message"; cell.textContent = "暂无方案，点击「创建方案」建立问题集合。";
        }
        for (const plan of result.items) {
            const row = rows.insertRow();
            [plan.id, plan.name, plan.projectName || "项目不可用", plan.repeatCount + " 次", plan.updatedAt?.replace("T", " ") || "—"].forEach(value => {
                row.insertCell().textContent = value;
            });
            row.insertCell().append(
                button("查看", () => openPlan(plan.id, true).catch(exception => error(exception.message))),
                button("编辑", () => openPlan(plan.id).catch(exception => error(exception.message))));
        }
        byId("page-info").textContent = "第 " + (page + 1) + " 页 · 共 " + result.total + " 个方案";
        byId("previous-page").disabled = page === 0;
        byId("next-page").disabled = (page + 1) * 10 >= result.total;
    }
    function canDiscard() {
        if (!dirty) return true;
        byId("discard-prompt").hidden = false;
        byId("keep-editing").focus();
        return false;
    }
    async function openPlan(id = null, readonly = false) {
        if (!ready || saving || !canDiscard()) return;
        // 详情成功回读后再替换编辑状态，加载失败保留当前输入。
        const detail = id ? await request("/diagnosis-plans/" + id) : null;
        viewOnly = readonly;
        revision++; planId = id; dirty = false; candidates = []; questions = detail ? detail.questions.map(question => ({...question})) : [];
        questionPage = 0;
        form.reset(); error(""); message("plan-success", "");
        byId("candidates-panel").hidden = true;
        byId("discard-prompt").hidden = true;
        byId("generation-status").textContent = "结合用户意图、使用场景和侧面需求生成。";
        byId("editor-title").textContent = id ? (viewOnly ? "查看方案 #" : "编辑方案 #") + id : "创建方案";
        byId("editor-description").textContent = viewOnly ? "展示已保存的配置和问题集合。" : "生成候选供审核，确认后的问题才会保存到方案。";
        byId("plan-config").hidden = viewOnly;
        byId("generator").hidden = viewOnly;
        byId("add-question").hidden = viewOnly;
        byId("save-plan").hidden = viewOnly;
        const overview = byId("plan-overview");
        overview.hidden = !viewOnly;
        overview.replaceChildren();
        if (viewOnly) {
            for (const [label, value] of [["方案名称", detail.name], ["关联项目", detail.projectName || "项目不可用"],
                ["每题每渠道重复执行次数", detail.repeatCount + " 次"], ["被测模型渠道", detail.channelIds.map(id =>
                    channels.find(channel => channel.id === id)?.channelName || "不可用渠道 #" + id).join("、")]]) {
                const item = document.createElement("div");
                item.className = "overview-item";
                if (label === "被测模型渠道") item.classList.add("overview-channels");
                const caption = document.createElement("span");
                caption.className = "overview-label"; caption.textContent = label;
                const content = document.createElement("strong");
                content.textContent = value;
                item.append(caption, content); overview.append(item);
            }
        }
        if (detail) {
            form.elements.name.value = detail.name; form.elements.projectId.value = detail.projectId;
            form.elements.repeatCount.value = detail.repeatCount;
        }
        for (const checkbox of byId("test-channels").querySelectorAll("input")) {
            checkbox.checked = detail?.channelIds.includes(Number(checkbox.value)) || false;
            checkbox.disabled = !channels.find(channel => channel.id === Number(checkbox.value))?.enabled;
        }
        if (!editor.open) editor.showModal();
        if (!viewOnly && detail && detail.channelIds.some(id => !channels.some(channel => channel.id === id && channel.enabled)))
            error("方案包含已停用或不可用的测试渠道，请取消勾选并重新选择。");
        // 停用的已选项允许取消，不能重新作为新渠道勾选。
        for (const checkbox of byId("test-channels").querySelectorAll("input")) {
            if (checkbox.disabled && checkbox.checked) {
                checkbox.disabled = false;
                checkbox.addEventListener("change", () => { if (!checkbox.checked) checkbox.disabled = true; }, {once:true});
            }
        }
        // 母题只用来改写和审核对照，默认中文，不随方案里已有问题的语言变化；生成时至少一个地域，打开时先放一行空地域
        byId("generation-language").value = "中文";
        localeRows = [{region:"", language:""}]; renderLocaleRows(); renderProjectMarket();
        renderQuestions(); updateCount();
        document.body.classList.add("plan-dialog-open");
        editor.querySelector(".dialog-body").scrollTop = 0;
        (viewOnly ? byId("close-editor") : form.elements.name).focus({preventScroll:true});
    }
    function labeledSelect(title, choices, selected, onChange) {
        const label = document.createElement("label"); label.textContent = title;
        const select = document.createElement("select");
        Object.entries(choices).forEach(([value, text]) => select.add(new Option(text, value)));
        select.value = selected; select.setAttribute("aria-label", title);
        select.addEventListener("change", () => { onChange(select.value); markDirty(); });
        label.append(select); return label;
    }
    // 分页仅控制展示，完整集合一直保留在内存中，保存时一起提交。
    function questionTable(items, offset = 0, candidate = false) {
        const table = document.createElement("table"); table.className = "question-table";
        if (candidate) table.classList.add("candidate-table");
        const head = table.createTHead().insertRow();
        const titles = ["序号", "问题内容", "来源", "提问意图", "品牌范围", "语言", "地域"];
        if (candidate) titles.unshift("选用");
        else if (!viewOnly) titles.push("操作");
        titles.forEach(title => {
            const cell = document.createElement("th"); cell.scope = "col"; cell.textContent = title; head.append(cell);
        });
        const body = table.createTBody();
        items.forEach((question, position) => {
            const index = offset + position, row = body.insertRow(), prefix = (candidate ? "候选" : "问题") + (index + 1);
            if (candidate) {
                const check = document.createElement("input");
                check.type = "checkbox"; check.checked = true; check.dataset.candidateIndex = index;
                check.setAttribute("aria-label", "候选 " + (index + 1)); row.insertCell().append(check);
            }
            row.insertCell().textContent = index + 1;
            if (viewOnly && !candidate) {
                [question.text, question.source === "GENERATED" ? "模型生成" : "人工维护",
                    intentLabels[question.intent], scopeLabels[question.brandScope], question.language, question.region || "不分地域"]
                    .forEach(value => { row.insertCell().textContent = value; });
                return;
            }
            const textarea = document.createElement("textarea");
            textarea.rows = 2; textarea.value = question.text; textarea.maxLength = 2000; textarea.required = !candidate;
            textarea.setAttribute("aria-label", prefix + "内容");
            textarea.addEventListener("input", () => { question.text = textarea.value; if (!candidate) markDirty(); });
            const textCell = row.insertCell();
            textCell.append(textarea);
            // 按地域改写的候选附上对应母题，审核时对照意图；母题不随问题保存
            if (question.baseText) {
                const base = document.createElement("p");
                base.className = "question-base"; base.textContent = "母题：" + question.baseText;
                textCell.append(base);
            }
            row.insertCell().textContent = question.source === "GENERATED" ? "模型生成" : "人工维护";
            for (const [key, title, choices] of [["intent", "提问意图", intentLabels], ["brandScope", "品牌范围", scopeLabels]]) {
                const select = labeledSelect(title, choices, question[key], value => question[key] = value).querySelector("select");
                select.setAttribute("aria-label", prefix + title); row.insertCell().append(select);
            }
            const language = document.createElement("input");
            language.value = question.language; language.maxLength = 100; language.required = !candidate;
            language.setAttribute("aria-label", prefix + "语言");
            language.addEventListener("input", () => { question.language = language.value; if (!candidate) markDirty(); });
            row.insertCell().append(language);
            // 地域可空，空白保存为不分地域；输入时提示本方案已用过的地域，减少同一地域的不同写法
            const region = document.createElement("input");
            region.value = question.region || ""; region.maxLength = 50; region.placeholder = "不分地域";
            region.setAttribute("list", "region-options");
            region.setAttribute("aria-label", prefix + "地域");
            region.addEventListener("input", () => { question.region = region.value; if (!candidate) markDirty(); });
            row.insertCell().append(region);
            if (!candidate) row.insertCell().append(button("删除", () => { questions.splice(index, 1); markDirty(); renderQuestions(); }));
        });
        return table;
    }
    function renderQuestions() {
        const list = byId("question-list"); list.replaceChildren();
        const pages = Math.max(1, Math.ceil(questions.length / questionPageSize));
        questionPage = Math.min(questionPage, pages - 1);
        const offset = questionPage * questionPageSize;
        if (questions.length) list.append(questionTable(questions.slice(offset, offset + questionPageSize), offset));
        else {
            const empty = document.createElement("p"); empty.className = "help"; empty.textContent = "集合为空。可生成候选或添加自定义问题。"; list.append(empty);
        }
        byId("question-pagination").hidden = !questions.length;
        byId("question-page-info").textContent = "每页 20 条 · " + (offset + 1) + "–" + Math.min(offset + questionPageSize, questions.length) + " / " + questions.length + " 条";
        byId("question-page").replaceChildren();
        for (let index = 0; index < pages; index++) byId("question-page").add(new Option("第 " + (index + 1) + " / " + pages + " 页", index));
        byId("question-page").value = questionPage;
        byId("question-previous").disabled = questionPage === 0;
        byId("question-next").disabled = questionPage === pages - 1;
        renderRegionOptions();
        updateCount();
    }
    // 地域输入提示：本方案问题、候选和地域行里出现过的地域，减少同一地域的不同写法
    function renderRegionOptions() {
        const regions = new Set([...questions, ...candidates, ...localeRows].map(item => (item.region || "").trim()).filter(Boolean));
        byId("region-options").replaceChildren(...[...regions].map(region => new Option(region)));
    }
    // 项目目标市场、目标语言原文只作选择地域和语言的参考，不自动拆成地域，也不发给制题模型
    function renderProjectMarket() {
        const project = projects.find(item => item.id === Number(form.elements.projectId.value));
        byId("project-market").textContent = project
            ? "项目目标市场：" + (project.targetMarket || "未填写") + "；目标语言：" + (project.targetLanguage || "未填写") + "（仅供参考）"
            : "项目目标市场：请先选择项目";
    }
    // 按地域本地化的设置行：每行一个地域和一个语言，语言留空用该地域常用语言；只影响本次生成
    function renderLocaleRows() {
        const container = byId("locale-rows"); container.replaceChildren();
        localeRows.forEach((row, index) => {
            const line = document.createElement("div"); line.className = "locale-row";
            const region = document.createElement("input");
            region.value = row.region; region.maxLength = 50; region.placeholder = "地域，如 美国、东南亚";
            region.setAttribute("list", "region-options"); region.setAttribute("aria-label", "第 " + (index + 1) + " 行地域");
            region.addEventListener("input", () => { row.region = region.value; });
            const language = document.createElement("input");
            language.value = row.language; language.maxLength = 100; language.placeholder = "语言，留空用当地常用语言";
            language.setAttribute("aria-label", "第 " + (index + 1) + " 行语言");
            language.addEventListener("input", () => { row.language = language.value; });
            line.append(region, language, button("删除", () => { localeRows.splice(index, 1); renderLocaleRows(); }));
            container.append(line);
        });
        renderRegionOptions();
    }
    // 至少一个地域，每行必须填写地域，同一地域和语言的组合不重复；生成总数不能超过方案剩余容量，避免生成后无法加入
    function validateLocaleRows(rows, count) {
        if (!rows.length) return "请至少添加一个地域";
        if (rows.some(row => !row.region)) return "请填写每一行的地域，或删除空行";
        if (new Set(rows.map(row => normalize(row.region) + "|" + normalize(row.language))).size !== rows.length) return "地域和语言的组合不能重复";
        const remaining = 500 - questions.length;
        if (rows.length && count * rows.length > remaining) {
            return "本次将生成 " + count * rows.length + " 个候选，超过方案剩余容量 " + remaining + " 个，请减少生成数量或地域";
        }
        return "";
    }
    // 逐行把母题改写到各地域，一次请求只改写一个地域并显示进度；任一地域失败则本次生成失败，已有问题和候选保留
    async function localizeCandidates(projectId, channelCode, rows, base, startedRevision) {
        const result = [];
        for (const [index, row] of rows.entries()) {
            byId("generation-status").textContent = "正在按地域改写：" + row.region + "（" + (index + 1) + "/" + rows.length + "）";
            let localized;
            try {
                localized = await request("/diagnosis-plans/localize-questions", {method:"POST",
                    body:JSON.stringify({projectId, channelCode, region:row.region, language:row.language || null, questions:base})});
            } catch (exception) {
                throw new Error(row.region + " 改写失败：" + exception.message);
            }
            if (revision !== startedRevision) return null;
            // 改写结果与母题按顺序一一对应，记下母题供审核对照
            localized.forEach((question, position) => result.push({...question, baseText:base[position].text}));
        }
        return result;
    }
    function updateCount() {
        const selected = [...byId("test-channels").querySelectorAll("input:checked")].length;
        byId("question-count").textContent = questions.length + " / 500";
        byId("sample-count").textContent = "预计 " + (questions.length * selected * (Number(form.elements.repeatCount.value) || 0)) + " 次诊断";
    }
    byId("generate").addEventListener("click", async () => {
        if (generating || saving) return;
        const projectId = Number(form.elements.projectId.value), count = Number(byId("generation-count").value), language = byId("generation-language").value.trim();
        if (!projectId || !Number.isInteger(count) || count < 1 || count > 100 || !language) { error("请先选择项目，并填写 1 到 100 的生成数量及母题语言"); return; }
        const rows = localeRows.map(row => ({region:row.region.trim(), language:row.language.trim()}));
        const rowError = validateLocaleRows(rows, count);
        if (rowError) { error(rowError); return; }
        const startedRevision = revision, channelCode = byId("generation-channel").value || null;
        generating = true; byId("generate").disabled = true; error("");
        byId("generation-status").textContent = "正在生成母题，请稍候…";
        try {
            // 1. 生成一套母题，告诉模型本次覆盖哪些地域；再逐行改写到各地域，母题本身不进候选
            const base = await request("/diagnosis-plans/generate-questions", {method:"POST",
                body:JSON.stringify({projectId, count, language, channelCode, regions:rows.map(row => row.region)})});
            if (revision !== startedRevision) return;
            const result = await localizeCandidates(projectId, channelCode, rows, base, startedRevision);
            if (!result) return;
            // 2. 展示候选供审核，确认后才加入问题集合
            candidates = result; byId("candidate-list").replaceChildren();
            byId("candidate-list").append(questionTable(candidates, 0, true));
            byId("candidates-panel").hidden = false;
            renderRegionOptions();
            byId("generation-status").textContent = "已生成 " + candidates.length + " 个候选，请审核后加入集合。";
        } catch (exception) {
            if (revision === startedRevision) {
                error(exception.message);
                byId("generation-status").textContent = exception.message;
            }
        } finally { generating = false; byId("generate").disabled = false; }
    });
    byId("add-candidates").addEventListener("click", () => {
        const selected = [...byId("candidate-list").querySelectorAll("input:checked")].map(input => candidates[Number(input.dataset.candidateIndex)]);
        const existing = new Set(questions.map(question => normalize(question.text)));
        const added = [];
        for (const candidate of selected) {
            if (!candidate.text.trim() || !candidate.language.trim()) { error("候选内容和语言不能为空"); return; }
            const key = normalize(candidate.text);
            // 母题只用于审核对照，不随问题保存
            const {baseText, ...question} = candidate;
            if (!existing.has(key)) { added.push(question); existing.add(key); }
        }
        if (questions.length + added.length > 500) { error("每个方案最多 500 个问题"); return; }
        if (added.length) questionPage = Math.floor(questions.length / questionPageSize);
        questions.push(...added); markDirty(); renderQuestions(); error("");
        byId("generation-status").textContent = "本次加入 " + added.length + " 个问题；重复内容已跳过。";
        byId("candidates-panel").hidden = true; candidates = [];
    });
    byId("add-question").addEventListener("click", () => {
        if (questions.length >= 500) { error("每个方案最多 500 个问题"); return; }
        questions.push({text:"", source:"MANUAL", intent:"RECOMMENDATION", brandScope:"NON_BRAND", language:byId("generation-language").value.trim() || "中文", region:""});
        questionPage = Math.floor((questions.length - 1) / questionPageSize);
        markDirty(); renderQuestions(); byId("question-list").querySelector("tbody tr:last-child textarea").focus();
    });
    form.addEventListener("submit", async event => {
        event.preventDefault(); error("");
        if (saving || viewOnly) return;
        const invalidIndex = questions.findIndex(question => !question.text.trim() || !question.language.trim());
        if (invalidIndex >= 0) {
            questionPage = Math.floor(invalidIndex / questionPageSize);
            renderQuestions(); error("问题 " + (invalidIndex + 1) + " 的内容和语言不能为空"); return;
        }
        const channelIds = [...byId("test-channels").querySelectorAll("input:checked")].map(input => Number(input.value));
        if (!questions.length || !channelIds.length) { error("请至少添加一个问题并选择一个被测渠道"); return; }
        saving = true; revision++;
        const controls = [...form.querySelectorAll("input, select, textarea, button")];
        const disabledStates = controls.map(control => control.disabled);
        controls.forEach(control => control.disabled = true);
        try {
            const saved = await request(planId ? "/diagnosis-plans/" + planId : "/diagnosis-plans", {method:planId ? "PUT" : "POST",
                body:JSON.stringify({name:form.elements.name.value.trim(), projectId:Number(form.elements.projectId.value), repeatCount:Number(form.elements.repeatCount.value), channelIds, questions})});
            planId = saved.id; questions = saved.questions; dirty = false; revision++;
            byId("discard-prompt").hidden = true;
            editor.close();
            message("plan-success", "方案已保存，ID：" + planId + "。尚未执行诊断。")
                .scrollIntoView({behavior:"smooth", block:"center"});
            try { await loadList(); }
            catch (exception) { error("方案已保存，但列表刷新失败：" + exception.message); }
        } catch (exception) { error(exception.message); }
        finally {
            controls.forEach((control, index) => control.disabled = disabledStates[index]);
            saving = false; byId("generate").disabled = generating;
        }
    });
    form.elements.projectId.addEventListener("change", () => {
        revision++; candidates = []; byId("candidates-panel").hidden = true; markDirty();
        renderProjectMarket();
        byId("generation-status").textContent = "项目已切换，请确认现有问题是否适用于新项目。";
    });
    byId("add-locale").addEventListener("click", () => {
        localeRows.push({region:"", language:""}); renderLocaleRows();
        byId("locale-rows").querySelector(".locale-row:last-child input").focus();
    });
    form.elements.name.addEventListener("input", markDirty);
    form.elements.repeatCount.addEventListener("input", () => { markDirty(); updateCount(); });
    byId("new-plan").disabled = true;
    byId("question-previous").addEventListener("click", () => { questionPage--; renderQuestions(); });
    byId("question-next").addEventListener("click", () => { questionPage++; renderQuestions(); });
    byId("question-page").addEventListener("change", event => { questionPage = Number(event.target.value); renderQuestions(); });
    byId("new-plan").addEventListener("click", () => openPlan().catch(exception => error(exception.message)));
    function closeEditor() {
        if (saving || !canDiscard()) return;
        editor.close();
    }
    byId("close-editor").addEventListener("click", closeEditor);
    editor.addEventListener("cancel", event => {
        event.preventDefault();
        if (!byId("discard-prompt").hidden) byId("discard-prompt").hidden = true;
        else closeEditor();
    });
    byId("keep-editing").addEventListener("click", () => {
        byId("discard-prompt").hidden = true;
        form.elements.name.focus({preventScroll:true});
    });
    byId("discard-changes").addEventListener("click", () => {
        if (saving) return;
        dirty = false; editor.close();
    });
    editor.addEventListener("click", event => {
        const bounds = editor.getBoundingClientRect();
        const outside = event.clientX < bounds.left || event.clientX > bounds.right
            || event.clientY < bounds.top || event.clientY > bounds.bottom;
        if (event.target === editor && outside) closeEditor();
    });
    editor.addEventListener("close", () => {
        document.body.classList.remove("plan-dialog-open");
        byId("discard-prompt").hidden = true;
        dirty = false; revision++;
    });
    byId("search-form").addEventListener("submit", event => { event.preventDefault(); page = 0; loadList().catch(exception => error(exception.message)); });
    byId("previous-page").addEventListener("click", () => { page--; loadList().catch(exception => error(exception.message)); });
    byId("next-page").addEventListener("click", () => { page++; loadList().catch(exception => error(exception.message)); });
    window.addEventListener("beforeunload", event => { if (dirty) { event.preventDefault(); event.returnValue = ""; } });
    Promise.all([loadOptions(), loadList()]).catch(exception => error(exception.message));
})();
