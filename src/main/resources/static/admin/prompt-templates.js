"use strict";
/**
 * 提示词管理页：选择提示词，编辑当前版本并保存为新版本，查看可用变量，载入历史版本后再保存即回滚。
 * <p>可维护的提示词与变量来自 /prompt-templates（后端 PromptTemplateKey）；保存总是新增一版，校验不通过时显示后端给出的原因。
 * 切换提示词、载入旧版本或离开页面前，有未保存的修改会先确认。</p>
 */
(() => {
    const byId = id => document.getElementById(id);
    const request = window.AdminHttp.requestJson;
    const editor = byId("prompt-content"), select = byId("prompt-key");
    // 当前打开的提示词（含当前版本号）与编辑框载入时的内容；编辑框与载入内容不同即有未保存的修改
    let selected = null, loadedContent = "", saving = false;
    // 每次打开提示词递增，较早发出的读取晚到时不再覆盖页面
    let openTicket = 0;
    const time = value => value ? value.replace("T", " ").slice(0, 19) : "—";
    const show = (id, text) => { byId(id).textContent = text; byId(id).hidden = !text; };
    const dirty = () => selected != null && editor.value !== loadedContent;
    const element = (tag, className, text) => {
        const node = document.createElement(tag);
        if (className) node.className = className;
        if (text != null) node.textContent = text;
        return node;
    };

    /** 读取可维护的提示词并填进下拉框，打开地址里 ?key= 指定的提示词，没有指定时打开第一个。 */
    async function loadPromptList() {
        const items = await request("/prompt-templates");
        select.replaceChildren(...items.map(item => new Option(item.title + (item.version == null ? "（未配置）" : ""), item.key)));
        const wanted = new URLSearchParams(location.search).get("key");
        select.value = items.some(item => item.key === wanted) ? wanted : items[0]?.key || "";
        if (select.value) await openPrompt(select.value);
    }

    /**
     * 打开一个提示词：读取当前版本与历史版本，编辑框显示当前内容；还没配置时编辑框为空，保存后成为第 1 版。
     * @param {string} key 提示词标识
     */
    async function openPrompt(key) {
        const ticket = ++openTicket;
        show("prompt-error", ""); show("prompt-success", "");
        const [prompt, versions] = await Promise.all([
            request("/prompt-templates/" + encodeURIComponent(key)),
            request("/prompt-templates/" + encodeURIComponent(key) + "/versions")]);
        if (ticket !== openTicket) return;
        selected = prompt;
        loadedContent = prompt.content || "";
        editor.value = loadedContent;
        editor.disabled = false;
        byId("prompt-version-note").textContent = prompt.version == null ? "还没有配置，保存后成为第 1 版"
            : "当前第 " + prompt.version + " 版 · 保存于 " + time(prompt.createdAt);
        renderVariables(prompt.variables);
        renderVersions(versions);
        renderButtons();
        history.replaceState(null, "", "?key=" + encodeURIComponent(key));
    }

    /**
     * 可用变量表：变量写法、说明，必填的标“必填”。
     * @param {{name:string, description:string, required:boolean}[]} variables 当前提示词的变量
     */
    function renderVariables(variables) {
        const rows = byId("prompt-variables");
        rows.replaceChildren();
        for (const variable of variables) {
            const row = rows.insertRow();
            const name = row.insertCell();
            name.append(element("code", null, "{{" + variable.name + "}}"));
            if (variable.required) name.append(element("span", "prompt-required", "必填"));
            row.insertCell().textContent = variable.description;
        }
    }

    /**
     * 历史版本列表，新的在前：当前版本标“当前”，其余可载入到编辑框。
     * @param {{version:number, createdAt:string}[]} versions 各版本
     */
    function renderVersions(versions) {
        const list = byId("prompt-versions");
        list.replaceChildren();
        if (!versions.length) list.append(element("li", "prompt-empty", "还没有版本"));
        for (const item of versions) {
            const row = element("li");
            row.append(element("span", null, "第 " + item.version + " 版 · " + time(item.createdAt)));
            if (item.version === selected.version) {
                row.append(element("span", "prompt-current", "当前"));
            } else {
                const load = element("button", "text-button", "载入"); load.type = "button";
                load.addEventListener("click", () => loadVersion(item.version).catch(error => show("prompt-error", error.message)));
                row.append(load);
            }
            list.append(row);
        }
    }

    /**
     * 把某一版的内容载入编辑框，不直接保存；保存后成为新的当前版本，即回滚。
     * @param {number} version 版本号
     */
    async function loadVersion(version) {
        if (dirty() && !window.confirm("编辑框里有未保存的修改，载入旧版本会覆盖。确定载入吗？")) return;
        const owner = selected.key;
        const prompt = await request("/prompt-templates/" + encodeURIComponent(owner) + "/versions/" + version);
        if (selected?.key !== owner) return;
        editor.value = prompt.content;
        show("prompt-error", "");
        show("prompt-success", "已载入第 " + version + " 版的内容，点「保存为新版本」后生效");
        renderButtons();
    }

    /** 保存为新版本：成功后编辑框内容即为当前版本，刷新版本号与历史；校验不通过时显示原因，编辑内容保留。 */
    async function savePrompt() {
        if (saving || !dirty()) return;
        saving = true; renderButtons(); show("prompt-error", ""); show("prompt-success", "");
        const owner = selected.key;
        try {
            const saved = await request("/prompt-templates/" + encodeURIComponent(owner) + "/versions",
                {method:"POST", body:JSON.stringify({content:editor.value})});
            if (selected?.key !== owner) return;
            await openPrompt(owner);
            await refreshOptionTitles();
            show("prompt-success", "已保存为第 " + saved.version + " 版");
        } catch (error) {
            show("prompt-error", error.message);
        } finally {
            saving = false; renderButtons();
        }
    }

    /** 保存后刷新下拉框里的“未配置”标记。 */
    async function refreshOptionTitles() {
        const items = await request("/prompt-templates");
        for (const option of select.options) {
            const item = items.find(candidate => candidate.key === option.value);
            if (item) option.textContent = item.title + (item.version == null ? "（未配置）" : "");
        }
    }

    /** 有未保存的修改时才能保存或放弃；保存中都不可点。 */
    function renderButtons() {
        byId("save-prompt").disabled = saving || !dirty();
        byId("reset-prompt").disabled = saving || !dirty();
        byId("save-prompt").textContent = saving ? "保存中…" : "保存为新版本";
    }

    select.addEventListener("change", () => {
        // 有未保存的修改时先确认，取消则回到原来的提示词
        if (dirty() && !window.confirm("编辑框里有未保存的修改，切换后会丢失。确定切换吗？")) {
            select.value = selected.key;
            return;
        }
        openPrompt(select.value).catch(error => show("prompt-error", error.message));
    });
    editor.addEventListener("input", renderButtons);
    byId("save-prompt").addEventListener("click", savePrompt);
    byId("reset-prompt").addEventListener("click", () => {
        editor.value = loadedContent;
        show("prompt-success", "");
        renderButtons();
    });
    window.addEventListener("beforeunload", event => {
        if (dirty()) { event.preventDefault(); event.returnValue = ""; }
    });
    loadPromptList().catch(error => show("prompt-error", error.message));
})();
