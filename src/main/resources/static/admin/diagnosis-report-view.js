"use strict";
/**
 * 诊断报告各页签的渲染：把报告汇总画成页面元素，系统页面与导出网页共用，保证两边内容一致。
 * <p>需要模型筛选或查看入口的函数，第一个参数都是 context：modelFilter 为当前筛选的模型 ID（空字符串为全部模型），
 * entries 决定「看证据」「看回答」入口的样子（页面上是按钮，导出网页里是打开弹窗的链接），trend 为 ⓪ 诊断大盘的趋势点，见 renderTab。</p>
 * <p>页签内的子页签（⓪ 的趋势指标、⑦ 的地域）是带 data-subtab 的链接，渲染时不绑定事件：页面用 selectSubtab 切换，导出网页由导出脚本切换。
 * 子页签可以套在另一组子页签里，切换只影响自己那一组。</p>
 * <p>⓪ 诊断大盘的折线图和柱状图用页面内 SVG 直接画：项目没有图表库，导出网页也要能脱网打开。</p>
 * <p>只渲染、不发请求，也不读写页面上的其他元素。报告汇总由后端 domain/service/diagnosis/report 下的各生成类产出，
 * 各页签渲染函数注明了数据来源，字段含义以后端为准。</p>
 */
(() => {
    const statusLabels = Object.freeze({PENDING:"待生成", RUNNING:"生成中", PAUSED:"已暂停", SUCCEEDED:"已完成", FAILED:"失败"});
    // 报告的两段：先判定每条回答，再生成报告内容；与后端 DiagnosisReportStage 一致
    const stageLabels = Object.freeze({JUDGE:"判定每条回答", CONTENT:"生成报告内容"});
    const strengthLabels = {FIRST_CHOICE:"首推", STRONG:"强推", PARALLEL:"并列", PASSING:"顺带一提", WITH_RESERVATION:"附带缺点", NOT_RECOMMENDED:"不推荐"};
    const intentLabels = {RECOMMENDATION:"推荐选择", PROBLEM_SOLVING:"解决问题", COMPARISON:"对比", PURCHASE:"购买决策", TECHNICAL:"技术细节", AWARENESS:"了解认知"};
    const sourceTypeLabels = {OWNED:"官网", PUBLISHED:"我方发布", THIRD_PARTY_TARGET:"提到目标的第三方", COMPETITOR:"提到竞品", OTHER:"其他"};
    const categoryLabels = {FILL_CONTENT_GAP:"补内容空缺", IMPROVE_CONTENT:"完善已有内容", ADD_EVIDENCE:"增加可信证据",
        CORRECT_INFORMATION:"纠正和统一信息", IMPROVE_ACCESS:"改善公开访问与检索", ADD_THIRD_PARTY:"补充第三方覆盖"};
    const evidenceStatusLabels = {ANSWER_STATED:"回答明确表达", INFERRED:"待验证推测"};
    const portrayalLabels = {POSITIVE:"正向", NEUTRAL:"中性", NEGATIVE:"负向", INSUFFICIENT:"证据不足"};
    const opportunityLabels = {COMPETITOR_OCCUPIED:"竞品占位", WHITE_SPACE:"空白题", MENTIONED_NOT_RECOMMENDED:"提到未推荐",
        NOT_FIRST_CHOICE:"推荐未首推", USER_FOCUS:"用户在意", PERCEPTION_GAP:"口碑改善", UNCOVERED_PLATFORM:"渠道空白"};
    // ⓪ 诊断大盘各条线的颜色：第一个给目标，其余依次给趋势图里的竞品
    const DASHBOARD_COLORS = Object.freeze(["#1f6feb", "#e5484d", "#30a46c", "#f5a524", "#8e4ec6", "#12a594"]);
    // 竞品对比的柱子超过这个数就改成横向排行：竖着排名称会挤在一起
    const DASHBOARD_COLUMN_LIMIT = 6;
    // 子 Tab 与诊断报告全景一致，第一个是打开报告时默认显示的；part 为页签对应的汇总字段，早期报告缺这部分时提示重新生成报告内容。⑦ 只有带地域的新报告才有，缺失时自行提示
    const tabs = Object.freeze([
        {key:"dashboard", title:"⓪ 诊断大盘", part:"dashboard", render:renderDashboard},
        {key:"overview", title:"① 诊断概览", render:renderOverview},
        {key:"visibility", title:"② 可见度与推荐", render:renderVisibility},
        {key:"brand", title:"③ 品牌认知", part:"brand", render:renderBrand},
        {key:"competitor", title:"④ 竞品对比", part:"competitor", render:renderCompetitor},
        {key:"source", title:"⑤ 引用与内容", part:"source", render:renderSource},
        {key:"advice", title:"⑥ 优化建议", part:"advice", render:renderAdvice},
        {key:"region", title:"⑦ 地域报告", render:renderRegion}
    ].map(Object.freeze));
    const percent = value => value == null ? "—" : (value * 100).toFixed(1) + "%";
    const element = (tag, className, text) => {
        const node = document.createElement(tag);
        if (className) node.className = className;
        if (text != null) node.textContent = text;
        return node;
    };

    /**
     * 渲染一个页签的内容；页面切换页签与导出网页逐个输出页签都走这里，保证两边内容一致。
     * @param {{modelFilter:string, entries:{evidence:function(string[]):HTMLElement, answer:function(number):HTMLElement}, trend?:object[]}} context
     *     modelFilter 为当前筛选的模型 ID，空字符串为全部模型；entries.evidence 按问题编号生成「看证据」入口，entries.answer 按诊断结果 ID 生成「看回答」入口；
     *     trend 为报告详情的 dashboardTrend（同方案历次任务的大盘数字，最后一个是本报告），只有 ⓪ 诊断大盘使用
     * @param {HTMLElement} panel 内容写入的容器
     * @param {string} key 页签标识，取自 tabs
     * @param {object} summary 已完成报告的冻结汇总
     */
    function renderTab(context, panel, key, summary) {
        const tab = tabs.find(item => item.key === key);
        // 早期报告生成时还没有这部分内容，提示重新生成报告内容
        if (tab.part && !summary[tab.part]) panel.append(missingContentNotice());
        else tab.render(context, panel, summary);
    }

    /**
     * @param {object} detail 报告详情
     * @return {string} 报告所属方案、项目、诊断任务、判定模型、报告语言与状态的一行说明，生成中或已暂停时注明所在段；详情抬头与导出网页抬头共用
     */
    function buildReportNote(detail) {
        const judge = detail.judgeChannel ? (detail.judgeChannel.channelName || "—") + " · " + (detail.judgeChannel.modelName || "—") : "未开始";
        const inStage = detail.status === "RUNNING" || detail.status === "PAUSED";
        const status = (statusLabels[detail.status] || detail.status) + (inStage ? "（" + stageLabels[detail.stage] + "）" : "");
        return (detail.planName || "—") + " / " + (detail.projectName || "—") + " · 诊断任务 #" + detail.diagnosisTaskId
            + " · 判定模型：" + judge + " · 报告语言：" + (detail.reportLanguage || "中文") + " · " + status;
    }

    /**
     * 已选模型时的筛选说明，提醒哪些表格只含该模型、哪些仍是全部模型合计。
     * @param {string} modelName 所选模型的显示名称
     * @return {HTMLElement} 说明段落
     */
    function buildModelFilterNote(modelName) {
        return element("p", "report-filter-note", "已筛选模型：" + modelName + "。按模型分行的表格只显示该模型；维度排行、链接与清单为全部模型合计。");
    }

    /** @return {HTMLElement} 早期报告缺少某部分内容时页签里的提示；页面与导出网页共用，入口是系统里已完成报告的「重新生成报告内容」 */
    function missingContentNotice() {
        return element("div", "report-progress", "这份报告生成时还没有这部分内容，可在系统中点「重新生成报告内容」补上。");
    }


    /**
     * ⓪ 诊断大盘：给决策者看的首页签，一屏三段：被看见（声量与排名）、被信任（信源与准确）、被喜欢（情感与卖点），
     * 最下面是品牌趋势和竞品对比。
     * 数据：summary.dashboard 由 ReportDashboardSummarizer 生成；趋势点 context.trend 是同方案历次任务的大盘数字（最后一个是本报告），
     * 由报告详情接口现读。选了模型筛选时全部换成该模型的数字，不选时是所有模型合计。
     * 卡片下的「较上次」拿趋势的最后两个点相比；没有更早的点时不显示，趋势图只有一个点。
     * @param {object} context 模型筛选、查看入口与趋势点，见 renderTab
     * @param {HTMLElement} panel 内容写入的容器
     * @param {object} summary 报告汇总
     */
    function renderDashboard(context, panel, summary) {
        // 1. 趋势点换成当前筛选范围内的数字；某个更早的任务没测所选模型时，该点不参与
        const trend = context.trend?.length ? context.trend : [{current:true, dashboard:summary.dashboard}];
        const points = trend.map((point, index) => ({time:point.time, scope:dashboardScope(context, point.dashboard),
            changed:index > 0 && isDashboardSetupChanged(trend[index - 1].dashboard, point.dashboard)})).filter(point => point.scope);
        const current = points[points.length - 1], previous = points[points.length - 2] || null;

        // 2. 三段指标：被看见一整行，被信任与被喜欢并排
        const second = element("div", "report-dashboard-pair");
        second.append(renderDashboardTrust(current, previous), renderDashboardFavor(current, previous));
        // 3. 趋势与竞品对比并排
        const charts = element("div", "report-dashboard-charts");
        charts.append(...renderDashboardCharts(points, dashboardTimeLabels(points), current, previous));
        panel.append(renderDashboardVisibility(current, previous), second, charts);
    }

    /**
     * 取一份大盘数字里当前筛选范围的那一份。
     * @param {object} context 模型筛选，见 renderTab
     * @param {object} dashboard 一次任务的大盘数字
     * @return {object|undefined} 未筛选时为合计；筛选了模型时为该模型的数字，这次任务没测该模型时为 undefined
     */
    function dashboardScope(context, dashboard) {
        return context.modelFilter ? dashboard.models.find(model => String(model.channelId) === context.modelFilter) : dashboard.total;
    }

    /**
     * 判断相邻两次任务的题目或模型有没有调整；调整过的点在趋势图上画成空心，提醒前后的数不完全可比。
     * @param {object} before 前一次任务的大盘数字
     * @param {object} after 后一次任务的大盘数字
     * @return {boolean} 非品牌题数或被测模型不同时为 true
     */
    function isDashboardSetupChanged(before, after) {
        const modelIds = dashboard => dashboard.models.map(model => model.channelId).sort().join(",");
        return before.questions !== after.questions || modelIds(before) !== modelIds(after);
    }

    /**
     * 趋势图横轴的文字：默认只写月-日；同一天跑过多次任务时带上时分，避免几个点看起来是同一个。
     * @param {Array<{time:string}>} points 趋势点，time 为 ISO 时间；只有本报告一个点且没有时间时为 undefined
     * @return {string[]} 与趋势点一一对应的横轴文字
     */
    function dashboardTimeLabels(points) {
        const days = points.map(point => (point.time || "").slice(5, 10));
        const repeated = days.some((day, index) => days.indexOf(day) !== index);
        return points.map((point, index) => repeated ? days[index] + " " + (point.time || "").slice(11, 16) : days[index]);
    }

    /**
     * 大盘里一段指标的标题：彩色标签加副标题，如「被看见 声量与排名」。
     * @param {string} tone 标签颜色：seen、trust、favor
     * @param {string} tag 标签文字
     * @param {string} subtitle 副标题
     * @return {HTMLElement} 段标题
     */
    function dashboardHeading(tone, tag, subtitle) {
        const heading = element("h3", "report-dashboard-heading");
        heading.append(element("span", "report-dashboard-tag report-dashboard-tag-" + tone, tag), element("span", null, subtitle));
        return heading;
    }

    /**
     * 被看见 · 声量与排名：提及率、首推率、前三率、平均排名、品牌提及份额五张卡。
     * @param {{scope:object}} current 本报告的点
     * @param {{scope:object}|null} previous 上一次任务的点，没有时为 null
     * @return {HTMLElement} 这一段
     */
    function renderDashboardVisibility(current, previous) {
        const target = scope => scope.brands.find(brand => brand.target);
        const now = target(current.scope), before = previous ? target(previous.scope) : null;
        const cards = element("div", "report-dashboard-cards report-dashboard-cards-5");
        cards.append(dashboardCard("提及率", percent(now.mentionRate), rateChange(now.mentionRate, before?.mentionRate)),
            dashboardCard("首推率", percent(now.firstChoiceRate), rateChange(now.firstChoiceRate, before?.firstChoiceRate)),
            dashboardCard("前三率", percent(now.top3Rate), rateChange(now.top3Rate, before?.top3Rate)),
            dashboardCard("平均排名", now.averageRank == null ? "—" : now.averageRank.toFixed(2), rankChange(now.averageRank, before?.averageRank)),
            dashboardCard("品牌提及份额", percent(now.mentionShare), rateChange(now.mentionShare, before?.mentionShare)));
        const node = element("section", "report-dashboard-block");
        node.append(dashboardHeading("seen", "被看见", "声量与排名"), cards);
        return node;
    }

    /**
     * 被信任 · 信源与准确：我方内容被引用次数、被引用率、官网引用率、描述准确率四张卡。
     * @param {{scope:object}} current 本报告的点
     * @param {{scope:object}|null} previous 上一次任务的点，没有时为 null
     * @return {HTMLElement} 这一段
     */
    function renderDashboardTrust(current, previous) {
        const now = current.scope.reputation, before = previous?.scope.reputation;
        const cards = element("div", "report-dashboard-cards report-dashboard-cards-2");
        cards.append(dashboardCard("我方内容被引用次数", now.ownCitations == null ? "—" : String(now.ownCitations), countChange(now.ownCitations, before?.ownCitations, "次")),
            dashboardCard("我方内容被引用率", percent(now.ownCitedRate), rateChange(now.ownCitedRate, before?.ownCitedRate)),
            dashboardCard("官网引用率", percent(now.ownedRate), rateChange(now.ownedRate, before?.ownedRate)),
            dashboardCard("描述准确率", percent(now.accurateRate), rateChange(now.accurateRate, before?.accurateRate)));
        const node = element("section", "report-dashboard-block");
        node.append(dashboardHeading("trust", "被信任", "信源与准确"), cards);
        return node;
    }

    /**
     * 被喜欢 · 情感与卖点：点名问题的评价分布（正面、中性、负面的占比条），以及品牌正面率、卖点提及率两张卡。
     * @param {{scope:object}} current 本报告的点
     * @param {{scope:object}|null} previous 上一次任务的点，没有时为 null
     * @return {HTMLElement} 这一段
     */
    function renderDashboardFavor(current, previous) {
        const now = current.scope.reputation, before = previous?.scope.reputation;
        const box = element("div", "report-dashboard-panel");
        box.append(element("p", "report-dashboard-label", "品牌情感分布"));
        if (now.sentimentAnswers) {
            const bar = element("div", "report-dashboard-stack"), legend = element("div", "report-dashboard-legend report-dashboard-stack-legend");
            for (const [tone, label, count] of [["positive", "正面", now.positive], ["neutral", "中性", now.neutral], ["negative", "负面", now.negative]]) {
                const share = count / now.sentimentAnswers, part = element("i", "report-dashboard-stack-" + tone), item = element("span", null, label + " " + percent(share));
                part.style.width = share * 100 + "%";
                part.title = label + " " + count + " 条";
                bar.append(part);
                item.prepend(element("i", "report-dashboard-stack-" + tone));
                legend.append(item);
            }
            box.append(bar, legend);
        } else {
            box.append(element("p", "report-empty", "暂无数据"));
        }
        const cards = element("div", "report-dashboard-cards report-dashboard-cards-2");
        cards.append(dashboardCard("品牌正面率", percent(now.positiveRate), rateChange(now.positiveRate, before?.positiveRate)),
            dashboardCard("卖点提及率", percent(now.sellingPointRate), rateChange(now.sellingPointRate, before?.sellingPointRate)));
        const node = element("section", "report-dashboard-block");
        node.append(dashboardHeading("favor", "被喜欢", "情感与卖点"), box, cards);
        return node;
    }

    /**
     * 品牌趋势与竞品对比两块图：趋势可切换提及率、首推率、前三率、平均排名；对比是目标与全部预设竞品的提及份额。
     * @param {Array<{scope:object, changed:boolean}>} points 趋势点
     * @param {string[]} labels 趋势横轴文字
     * @param {{scope:object}} current 本报告的点
     * @param {{scope:object}|null} previous 上一次任务的点，没有时为 null
     * @return {HTMLElement[]} 趋势图区与对比图区
     */
    function renderDashboardCharts(points, labels, current, previous) {
        const target = scope => scope.brands.find(brand => brand.target);
        const now = target(current.scope), before = previous ? target(previous.scope) : null;

        // 对比：按提及份额从高到低，份额相同并列同一名次；标题下写名次、份额与较上次
        const ranked = [...current.scope.brands].sort((left, right) => (right.mentionShare ?? -1) - (left.mentionShare ?? -1));
        const place = 1 + ranked.filter(brand => (brand.mentionShare ?? -1) > (now.mentionShare ?? -1)).length;
        const hasShare = now.mentionShare != null;
        const shareItems = ranked.map(brand => ({label:brand.brandName, value:brand.mentionShare ?? 0, text:percent(brand.mentionShare), primary:brand.target}));
        const compare = dashboardPanel("竞品对比 · 品牌提及份额", null,
            !hasShare ? element("p", "report-empty", "暂无数据") : shareItems.length > DASHBOARD_COLUMN_LIMIT ? rankBars(shareItems) : barChart(shareItems, true));
        if (hasShare) {
            const note = element("p", "report-dashboard-note", "我的品牌：第 " + place + " 名 · " + percent(now.mentionShare));
            const change = rateChange(now.mentionShare, before?.mentionShare);
            if (change) note.append(" · ", ...change.childNodes);
            compare.querySelector("h3").after(note);
        }

        // 趋势：目标一条线，加上本报告里提及份额最高的几个竞品（一种颜色一条线，再多就分不清）；全部竞品见右侧对比。
        // 更早的任务里没有这个竞品时该点留空。平均排名的纵轴是名次，第 1 名在最上面，线往上走都表示变好
        const brandNames = [now.brandName, ...ranked.filter(brand => !brand.target).slice(0, DASHBOARD_COLORS.length - 1).map(brand => brand.brandName)];
        const seriesOf = metric => brandNames.map((name, index) => ({name, primary:index === 0, color:DASHBOARD_COLORS[index % DASHBOARD_COLORS.length],
            values:points.map(point => point.scope.brands.find(brand => brand.brandName === name)?.[metric] ?? null)}));
        const trend = dashboardPanel("品牌趋势", null, dashboardMetricTabs("dashboard-trend",
            [["mentionRate", "提及率"], ["firstChoiceRate", "首推率"], ["top3Rate", "前三率"], ["averageRank", "平均排名"]],
            metric => lineChart(points, labels, seriesOf(metric), metric === "averageRank")));
        return [trend, compare];
    }

    /**
     * 大盘的一张指标卡：指标名、大号数字，以及可选的「较上次」。
     * @param {string} label 指标名
     * @param {string} value 已格式化的数值，没有数据时为“—”
     * @param {HTMLElement|null} change 「较上次」一行，没有可比数据时为 null
     * @return {HTMLElement} 指标卡
     */
    function dashboardCard(label, value, change) {
        const card = element("div", "report-dashboard-card");
        card.append(element("span", "report-dashboard-label", label), element("strong", "report-dashboard-value", value));
        if (change) card.append(change);
        return card;
    }

    /**
     * 比率类指标的「较上次」：写相差几个百分点，数值上升算变好。
     * @param {number|null} now 本次的比率
     * @param {number|null|undefined} before 上一次的比率
     * @return {HTMLElement|null} 「较上次」一行；任一次没有数据时为 null
     */
    function rateChange(now, before) {
        if (now == null || before == null) return null;
        const diff = Math.round((now - before) * 1000) / 10;
        return changeLine(diff, Math.abs(diff).toFixed(1) + " 个百分点", diff > 0);
    }

    /**
     * 平均排名的「较上次」：写相差多少名，排名数值下降才算变好，和比率相反。
     * @param {number|null} now 本次的平均排名
     * @param {number|null|undefined} before 上一次的平均排名
     * @return {HTMLElement|null} 「较上次」一行；任一次没有数据时为 null
     */
    function rankChange(now, before) {
        if (now == null || before == null) return null;
        const diff = Math.round((now - before) * 100) / 100;
        return changeLine(diff, Math.abs(diff).toFixed(2), diff < 0);
    }

    /**
     * 次数类指标的「较上次」：写相差多少次，数值上升算变好。
     * @param {number|null|undefined} now 本次的次数
     * @param {number|null|undefined} before 上一次的次数
     * @param {string} unit 单位
     * @return {HTMLElement|null} 「较上次」一行；任一次没有数据时为 null
     */
    function countChange(now, before, unit) {
        if (now == null || before == null) return null;
        return changeLine(now - before, Math.abs(now - before) + " " + unit, now > before);
    }

    /**
     * 「较上次」一行：箭头表示数值升降，颜色表示好坏（变好红色、变差绿色，沿用用户选定的参考图）。
     * @param {number} diff 本次减上一次
     * @param {string} amount 已格式化的变化幅度，不带正负号
     * @param {boolean} better 这个方向的变化是否算变好
     * @return {HTMLElement} 「较上次」一行；没有变化时写“持平”
     */
    function changeLine(diff, amount, better) {
        const line = element("p", "report-dashboard-change");
        if (diff === 0) line.append(element("span", null, "持平"));
        else line.append(element("span", better ? "report-dashboard-better" : "report-dashboard-worse", (diff > 0 ? "↗ " : "↘ ") + amount));
        line.append(element("span", null, " 较上次"));
        return line;
    }

    /**
     * 大盘里带标题的一块图表区。
     * @param {string} title 标题
     * @param {string|null} note 标题下的一行说明，没有时为 null
     * @param {HTMLElement|SVGElement} content 图表或提示
     * @return {HTMLElement} 图表区
     */
    function dashboardPanel(title, note, content) {
        const node = element("div", "report-dashboard-panel");
        node.append(element("h3", null, title));
        if (note) node.append(element("p", "report-dashboard-note", note));
        node.append(content);
        return node;
    }

    /**
     * 趋势图上方切换指标的一组子页签，每个指标一张图；用子页签的写法，页面与导出网页都能切换。
     * @param {string} groupName 子页签组的名字，同一页签内唯一
     * @param {Array<[string, string]>} metrics 指标的字段名与显示名
     * @param {function(string):SVGElement} chartOf 按指标字段名画图
     * @return {HTMLElement} 子页签组
     */
    function dashboardMetricTabs(groupName, metrics, chartOf) {
        const group = element("div", "report-subtab-group"); group.dataset.subtabGroup = groupName;
        const bar = element("div", "report-subtabs report-dashboard-metrics"); bar.setAttribute("role", "tablist");
        group.append(bar);
        metrics.forEach(([metric, title], index) => {
            const tab = element("a", "report-subtab", title);
            tab.href = "#"; tab.dataset.subtab = metric;
            tab.setAttribute("role", "tab"); tab.setAttribute("aria-selected", String(index === 0));
            bar.append(tab);
            const content = element("div"); content.dataset.subtabPanel = metric; content.hidden = index > 0;
            content.append(chartOf(metric));
            group.append(content);
        });
        return group;
    }

    /**
     * 创建 SVG 元素；项目没有图表库，导出网页又要脱网打开，大盘的图都用它直接画。
     * @param {string} tag 元素名
     * @param {Object<string, string|number>} attributes 属性
     * @param {string} [text] 文字内容，作为文本节点写入
     * @return {SVGElement} SVG 元素
     */
    function svgNode(tag, attributes, text) {
        const node = document.createElementNS("http://www.w3.org/2000/svg", tag);
        for (const [name, value] of Object.entries(attributes)) node.setAttribute(name, String(value));
        if (text != null) node.textContent = text;
        return node;
    }

    /**
     * 趋势折线图：横轴是历次任务；第一条线是目标，加粗。
     * 比率的纵轴固定 0–100%，目标线下铺底色；名次的纵轴从第 1 名（最上）到各线出现过的最差名次取整（最下），不铺底色。
     * 某个点没有数据时线在那里断开；题目或模型调整过的点画成空心；只有一个点时只画点。
     * @param {Array<{changed:boolean}>} points 趋势点
     * @param {string[]} labels 横轴文字
     * @param {Array<{name:string|null, primary:boolean, color:string, values:Array<number|null>}>} series 每条线的名称、颜色与各点的数值；name 为 null 时不出图例
     * @param {boolean} [asRank] 数值是否为名次，默认是比率
     * @return {HTMLElement} 图例加折线图
     */
    function lineChart(points, labels, series, asRank = false) {
        const width = 640, height = 280, left = asRank ? 62 : 46, right = 16, top = 12, bottom = 34;
        const plotWidth = width - left - right, plotHeight = height - top - bottom;
        const xOf = index => left + (points.length === 1 ? plotWidth / 2 : plotWidth * index / (points.length - 1));
        // 名次轴至少画到第 5 名，保证只有一两个名次时线不会贴在边上
        const worst = Math.max(5, Math.ceil(Math.max(...series.flatMap(line => line.values.filter(value => value != null)), 1)));
        const yOf = value => asRank ? top + plotHeight * (value - 1) / (worst - 1) : top + plotHeight * (1 - value);
        const format = value => asRank ? value.toFixed(2) : percent(value);
        const chart = svgNode("svg", {viewBox:"0 0 " + width + " " + height, class:"report-dashboard-svg", role:"img"});

        // 1. 纵轴刻度线与横轴文字
        const ticks = asRank ? Array.from({length:5}, (unused, index) => 1 + (worst - 1) * index / 4) : [0, 0.2, 0.4, 0.6, 0.8, 1];
        for (const tick of ticks) {
            const y = yOf(tick);
            chart.append(svgNode("line", {x1:left, x2:width - right, y1:y, y2:y, stroke:"#e6ebf2", "stroke-width":1}),
                svgNode("text", {x:left - 8, y:y + 4, "text-anchor":"end", "font-size":11, fill:"#8a98aa"},
                    asRank ? "第 " + (Number.isInteger(tick) ? tick : tick.toFixed(1)) + " 名" : Math.round(tick * 100) + "%"));
        }
        labels.forEach((label, index) => chart.append(svgNode("text", {x:xOf(index), y:height - 12, "text-anchor":"middle", "font-size":11, fill:"#8a98aa"}, label || "本次")));

        // 2. 每条线按连续有数据的点分段画；目标线下铺底色，画在最后盖住竞品线
        for (const line of [...series].reverse()) {
            const segments = [];
            line.values.forEach((value, index) => {
                if (value == null) return;
                const last = segments[segments.length - 1];
                if (last && last[last.length - 1].index === index - 1) last.push({index, value});
                else segments.push([{index, value}]);
            });
            for (const segment of segments) {
                const path = segment.map((point, order) => (order ? "L" : "M") + xOf(point.index).toFixed(1) + " " + yOf(point.value).toFixed(1)).join(" ");
                if (line.primary && segment.length > 1 && !asRank) {
                    chart.append(svgNode("path", {d:path + " L" + xOf(segment[segment.length - 1].index).toFixed(1) + " " + yOf(0) + " L" + xOf(segment[0].index).toFixed(1) + " " + yOf(0) + " Z",
                        fill:line.color, "fill-opacity":0.08, stroke:"none"}));
                }
                if (segment.length > 1) chart.append(svgNode("path", {d:path, fill:"none", stroke:line.color, "stroke-width":line.primary ? 2.5 : 1.5, "stroke-linejoin":"round"}));
                for (const point of segment) {
                    const hollow = points[point.index].changed;
                    const dot = svgNode("circle", {cx:xOf(point.index), cy:yOf(point.value), r:line.primary ? 4 : 3, fill:hollow ? "#ffffff" : line.color, stroke:line.color, "stroke-width":2});
                    dot.append(svgNode("title", {}, (line.name ? line.name + " · " : "") + (labels[point.index] || "本次") + " · " + format(point.value)
                        + (hollow ? " · 题目或模型有调整" : "")));
                    chart.append(dot);
                }
            }
        }

        // 3. 图例：只在有多条线时出现
        const box = element("div", "report-dashboard-chart");
        const named = series.filter(line => line.name);
        if (named.length > 1) {
            const legend = element("div", "report-dashboard-legend");
            for (const line of named) {
                const item = element("span", null, line.name), swatch = element("i");
                swatch.style.background = line.color;
                item.prepend(swatch);
                legend.append(item);
            }
            box.append(legend);
        }
        box.append(chart);
        return box;
    }

    /**
     * 横向排行：每项一行，名称、横条、数值；品牌多到竖着排不下时代替柱状图，横条长度相对最大的一项。
     * @param {Array<{label:string, value:number, text:string, primary?:boolean}>} items 已排好序的各项：名称、数值、数值文字与是否重点
     * @return {HTMLElement} 横向排行
     */
    function rankBars(items) {
        const highest = Math.max(...items.map(item => item.value), 0);
        const list = element("div", "report-dashboard-rank");
        for (const item of items) {
            const row = element("div", item.primary ? "report-dashboard-rank-row report-dashboard-rank-primary" : "report-dashboard-rank-row");
            const name = element("span", "report-dashboard-rank-name", item.label), track = element("span", "report-dashboard-rank-track"), fill = element("i");
            name.title = item.label;
            fill.style.width = (highest ? item.value / highest * 100 : 0) + "%";
            track.append(fill);
            row.append(name, track, element("span", "report-dashboard-rank-value", item.text));
            list.append(row);
        }
        return list;
    }

    /**
     * 柱状图：每项一根柱子，柱顶写数值、柱下写名称；重点项（目标）用深色，其余用浅色。
     * @param {Array<{label:string, value:number, text:string, primary?:boolean}>} items 每根柱子的名称、数值、柱顶文字与是否重点
     * @param {boolean} asRate 数值是否为比率：是则纵轴按百分比，否则按个数
     * @return {SVGElement} 柱状图
     */
    function barChart(items, asRate) {
        const width = 420, height = 280, left = 40, right = 12, top = 24, bottom = 36;
        const plotWidth = width - left - right, plotHeight = height - top - bottom;
        // 纵轴上限取整：比率按 10% 进位，个数按 5 的倍数进位，保证最高的柱子顶上还放得下数值
        const highest = Math.max(...items.map(item => item.value), 0);
        const ceiling = asRate ? Math.max(0.1, Math.ceil(highest * 10 - 1e-9) / 10) : Math.max(5, Math.ceil(highest / 5) * 5);
        const chart = svgNode("svg", {viewBox:"0 0 " + width + " " + height, class:"report-dashboard-svg", role:"img"});
        for (let tick = 0; tick <= 5; tick++) {
            const y = top + plotHeight * (1 - tick / 5), value = ceiling * tick / 5;
            chart.append(svgNode("line", {x1:left, x2:width - right, y1:y, y2:y, stroke:"#e6ebf2", "stroke-width":1}),
                svgNode("text", {x:left - 8, y:y + 4, "text-anchor":"end", "font-size":11, fill:"#8a98aa"}, asRate ? Math.round(value * 100) + "%" : String(value)));
        }
        const slot = plotWidth / items.length, barWidth = Math.min(44, slot * 0.6);
        items.forEach((item, index) => {
            const center = left + slot * (index + 0.5), barHeight = plotHeight * item.value / ceiling;
            const name = item.label.length > 7 ? item.label.slice(0, 6) + "…" : item.label;
            const bar = svgNode("rect", {x:center - barWidth / 2, y:top + plotHeight - barHeight, width:barWidth, height:barHeight, rx:4,
                fill:item.primary ? DASHBOARD_COLORS[0] : "#d6e4fb"});
            bar.append(svgNode("title", {}, item.label + " · " + item.text));
            chart.append(bar,
                svgNode("text", {x:center, y:top + plotHeight - barHeight - 6, "text-anchor":"middle", "font-size":12, "font-weight":600, fill:"#33465e"}, item.text),
                svgNode("text", {x:center, y:height - 14, "text-anchor":"middle", "font-size":11, fill:"#5a6f8a"}, name));
        });
        return chart;
    }

    /**
     * ① 诊断概览：样本说明、各模型核心指标、主要发现与前三条优先行动。
     * 数据：sample、models 由 ReportOverviewSummarizer 生成；主要发现与行动来自 advice（AdviceGenerator）与 issues（ReportIssueSummarizer）。
     * @param {object} context 模型筛选与查看入口，见 renderTab
     * @param {HTMLElement} panel 内容写入的容器
     * @param {object} summary 报告汇总
     */
    function renderOverview(context, panel, summary) {
        const sample = summary.sample, judge = sample.judgeChannel || {};
        const grid = element("div", "task-overview");
        for (const [label, value] of [["有效回答", sample.validAnswers], ["判定成功", sample.judged], ["判定失败", sample.judgeFailed],
            ["带来源回答", sample.withSources], ["实际联网回答", sample.searched], ["模型数", sample.models], ["题数", sample.questions],
            ["最大采样次数", sample.maxRepeat], ["判定模型", (judge.channelName || "—") + " · " + (judge.modelName || "—") + " · 提示词 " + (sample.promptVersion || "—")]]) {
            const item = element("div");
            item.append(element("span", null, label), element("strong", null, String(value ?? "—")));
            grid.append(item);
        }
        panel.append(section("样本说明", "只分析成功且未被截断的回答；依赖判定的指标只统计判定成功的回答。", grid));
        panel.append(section("各模型核心指标", "提及率、推荐率、首推率按非品牌问题计算；品牌正面率按点名问题计算；推荐份额 = 目标被推荐次数 ÷（目标 + 预设竞品被推荐次数）。",
            table(["模型", "判定回答", "提及率", "推荐率", "首推率", "品牌正面率", "推荐份额"], ofModel(context, summary.models).map(model => [
                model.channelName + " · " + model.modelName, model.judgedAnswers, percent(model.mentionRate), percent(model.recommendRate),
                percent(model.firstChoiceRate), percent(model.brandPositiveRate), percent(model.recommendShare)]))));
        panel.append(section("主要发现与优先行动", "由模型根据规则发现的问题归纳，每条都可以点「看证据」核对；完整内容见 ⑥ 优化建议。", renderKeyFindings(context, summary)));
    }

    /**
     * ① 概览中的主要发现与前三条建议，内容来自 summary.advice；早期报告缺建议或问题时提示重新生成报告内容。
     * @param {object} context 模型筛选与查看入口，见 renderTab
     * @param {object} summary 报告汇总
     * @return {HTMLElement} 主要发现与优先行动
     */
    function renderKeyFindings(context, summary) {
        const advice = summary.advice;
        if (!summary.issues || !summary.advice) return missingContentNotice();
        if (advice.status === "NO_ISSUES") return element("p", "report-empty", "规则没有发现需要处理的问题。");
        if (advice.status === "FAILED") return element("p", "report-empty", advice.errorMessage + "，见 ⑥ 优化建议。");
        const box = element("div", "report-dimension-list");
        box.append(element("h4", null, "主要发现"));
        for (const finding of advice.findings) box.append(adviceItem(context, finding.content, null, null, finding.issueIds));
        box.append(element("h4", null, "优先行动"));
        for (const action of advice.actions.slice(0, 3)) box.append(adviceItem(context, action.title, categoryLabels[action.category], action.content, action.issueIds));
        return box;
    }

    /**
     * ② 可见度与推荐：非品牌问题中的可见度、推荐强度，以及推荐理由与用户关注点。
     * 数据：models 由 ReportOverviewSummarizer 生成；strength、reservations、criteria、concerns 由 ReportVisibilitySummarizer 生成。
     * @param {object} context 模型筛选与查看入口，见 renderTab
     * @param {HTMLElement} panel 内容写入的容器
     * @param {object} summary 报告汇总
     */
    function renderVisibility(context, panel, summary) {
        panel.append(section("可见度（非品牌问题）", "问题覆盖率：至少有一次提到目标的题数占比；稳定性：同一题多次采样中提及结果一致的题数占比。",
            table(["模型", "非品牌回答", "提及率", "推荐率", "首推率", "平均排名", "问题覆盖率", "稳定性"], ofModel(context, summary.models).map(model => [
                model.channelName + " · " + model.modelName, model.nonBrandAnswers, percent(model.mentionRate), percent(model.recommendRate),
                percent(model.firstChoiceRate), model.averageRank ?? "—", percent(model.questionCoverage), percent(model.stability)]))));
        const strengthKeys = Object.keys(strengthLabels);
        panel.append(section("推荐强度（非品牌问题中提到目标时）", "提到不等于推荐得好：顺带一提、附带缺点都说明推荐不够坚定。点名与对比问题必然提到目标，不计入。",
            table(["模型", ...strengthKeys.map(key => strengthLabels[key])], ofModel(context, summary.strength).map(row => [
                row.channelName, ...strengthKeys.map(key => row.distribution[key] || 0)]))));
        const lists = element("div", "report-dimensions");
        lists.append(dimensionList(context, "目标的保留意见", "被推荐时附带的缺点，是要补的短板", summary.reservations),
            dimensionList(context, "用户选择标准", "未提到目标的回答强调了什么", summary.criteria),
            dimensionList(context, "用户顾虑", "未提到目标的回答提醒了什么", summary.concerns));
        panel.append(section("推荐理由与用户关注点", "按归一后的维度统计次数，附回答原文。", lists));
    }

    /**
     * ③ 品牌认知：只看点名问题，模型怎么评价目标、描述对不对、负面来自哪里、卖点说没说到。
     * 数据：summary.brand，由 ReportBrandSummarizer 生成。
     * @param {object} context 模型筛选与查看入口，见 renderTab
     * @param {HTMLElement} panel 内容写入的容器
     * @param {object} summary 报告汇总
     */
    function renderBrand(context, panel, summary) {
        const brand = summary.brand;
        panel.append(section("各模型评价（点名问题）", "情感按回答计数；描述准确率只统计判定给出了对错的回答，依据是项目资料；卖点覆盖率 = 至少说到一个核心卖点的回答占比。",
            table(["模型", "点名回答", "正面", "中性", "负面", "描述准确率", "卖点覆盖率"], ofModel(context, brand.models).map(model => [
                model.channelName + " · " + model.modelName, model.brandAnswers, model.positive, model.neutral, model.negative,
                percent(model.accurateRate), percent(model.sellingPointRate)]))));
        const lists = element("div", "report-dimensions");
        lists.append(dimensionList(context, "正面点", "继续强化的优势", brand.positives),
            dimensionList(context, "负面点", "需要回应或纠正", brand.negatives),
            dimensionList(context, "提到的卖点", "模型已经知道的核心卖点", brand.sellingPoints));
        panel.append(section("正面点、负面点与卖点", "按归一后的维度统计次数，附回答原文。", lists));
        panel.append(section("负面来源", "判定为负面的点名回答引用了哪些平台，决定去哪里处理。", table(["平台", "引用次数", "链接示例"],
            brand.negativeSources.map(row => [row.platform, row.count, row.links.map(link => link.title || link.url).join("；")]), 1)));
        panel.append(section("错误信息清单", "与项目资料不符的描述，资料没写的内容不判对错。", quoteList(context, ofModel(context, brand.factErrors).map(error => ({
            title:error.content, quote:error.quote, channelName:error.channelName, resultId:error.resultId})))));
    }

    /**
     * ④ 竞品对比：目标与预设竞品同口径比较，再看同题先后、竞品强在哪些场景、对比维度谁赢。
     * 数据：summary.competitor，由 ReportCompetitorSummarizer 生成。
     * @param {object} context 模型筛选与查看入口，见 renderTab
     * @param {HTMLElement} panel 内容写入的容器
     * @param {object} summary 报告汇总
     */
    function renderCompetitor(context, panel, summary) {
        const competitor = summary.competitor;
        panel.append(section("目标与竞品（非品牌问题）", "与 ② 的口径相同，分母为该模型的非品牌回答；对比问题点了竞品的名，不计入提及率。",
            table(["模型", "品牌", "提及率", "推荐率", "首推率"], ofModel(context, competitor.brands).map(row => [
                row.channelName + " · " + row.modelName, row.brandName + (row.target ? "（目标）" : ""),
                percent(row.mentionRate), percent(row.recommendRate), percent(row.firstChoiceRate)]), 2)));
        panel.append(section("同题胜率", "同一条回答里目标和竞品都被推荐且都有排名时，目标排在前面的占比。",
            table(["模型", "竞品", "可比较的回答", "目标在前", "竞品在前", "同题胜率"], ofModel(context, competitor.headToHead).map(row => [
                row.channelName + " · " + row.modelName, row.competitor, row.sameAnswers, row.targetAhead, row.competitorAhead, percent(row.winRate)]), 2)));
        panel.append(section("竞品的优势场景", "按问题意图拆分非品牌问题，只列出竞品推荐率高于目标的组合。",
            table(["模型", "问题意图", "竞品", "回答数", "目标推荐率", "竞品推荐率"], ofModel(context, competitor.advantageScenes).map(row => [
                row.channelName, intentLabels[row.intent] || row.intent, row.competitor, row.answers,
                percent(row.targetRecommendRate), percent(row.competitorRecommendRate)]), 3)));
        const lists = element("div", "report-dimensions");
        lists.append(dimensionList(context, "分维度胜负（对比问题）", "目标相对竞品在各维度上的结果", competitor.dimensions,
                row => "赢 " + row.win + " · 输 " + row.lose + " · 平 " + row.tie),
            dimensionList(context, "竞品被推荐的理由", "只取回答里明确写出的理由", competitor.competitorReasons));
        panel.append(section("对比维度与竞品理由", "按归一后的维度统计，附回答原文。", lists));
        panel.append(section("新出现的同类产品", "不在预设竞品里、回答中出现过的产品，可考虑加入竞品。",
            table(["产品", "出现回答数", "被推荐回答数"], competitor.candidates.map(row => [row.name, row.mentions, row.recommended]))));
    }

    /**
     * ⑤ 引用与内容：模型引用了哪些来源，我方内容有没有被用上，竞品靠哪些内容，用户关心的方向有没有我方内容。
     * 数据：summary.source，由 ReportSourceSummarizer 生成。
     * @param {object} context 模型筛选与查看入口，见 renderTab
     * @param {HTMLElement} panel 内容写入的容器
     * @param {object} summary 报告汇总
     */
    function renderSource(context, panel, summary) {
        const source = summary.source;
        panel.append(section("各模型来源情况", "来源覆盖率 = 带来源回答 ÷ 全部回答；各类引用率的分母为带来源回答。各平台“引用”含义不同，只在同类平台之间比较。",
            table(["模型", "回答", "带来源", "来源覆盖率", "引用官网", "引用我方发布", "引用提到目标的第三方", "引用提到竞品的内容"], ofModel(context, source.models).map(model => [
                model.channelName + " · " + model.modelName, model.answers, model.withSources, percent(model.sourceCoverage),
                percent(model.ownedRate), percent(model.publishedRate), percent(model.thirdPartyRate), percent(model.competitorRate)]))));
        panel.append(section("各模型常引用的平台", "按被引用次数排序，每个模型最多 10 个。", table(["模型", "平台", "引用次数"],
            ofModel(context, source.platforms).flatMap(row => row.platforms.map(platform => [row.channelName, platform.platform, platform.count])), 2)));
        panel.append(section("被引用的我方链接", "官网域名下的页面，以及与发布记录一致的链接；按生成报告时的发布记录判断。",
            table(["链接", "标题", "类型", "引用回答数"], source.ownLinks.map(link => [link.url, link.title || "—", sourceTypeLabels[link.sourceType] || link.sourceType, link.count]), 3)));
        panel.append(section("竞品的来源", "推荐了预设竞品的回答引用了哪些内容，看竞品被推荐靠的是什么。",
            table(["链接", "标题", "平台", "类型", "引用回答数"], source.competitorLinks.map(link => [link.url, link.title || "—", link.platform || "—",
                sourceTypeLabels[link.sourceType] || link.sourceType, link.count]), 4)));
        const gaps = element("div", "report-dimensions");
        gaps.append(dimensionList(context, "用户关心的方向", "引用我方为 0 的方向，说明我方内容没被用上（可能没有、不公开或不好找），需要人工确认",
            source.contentGaps, row => "提到 " + row.answers + " 条 · 引用我方 " + row.ownCitedAnswers + " 条"));
        panel.append(section("内容差距", "非品牌问题中回答强调的选择标准和用户顾虑，相关回答有没有引用官网或我方发布内容。", gaps));
    }

    /**
     * ⑥ 优化建议：模型写的建议与主要发现，以及规则发现的全部问题；每条都能打开证据。
     * 数据：advice 由 AdviceGenerator 调用模型生成，issues 由 ReportIssueSummarizer 按固定阈值生成。
     * @param {object} context 模型筛选与查看入口，见 renderTab
     * @param {HTMLElement} panel 内容写入的容器
     * @param {object} summary 报告汇总
     */
    function renderAdvice(context, panel, summary) {
        const advice = summary.advice;
        if (advice.status === "SUCCEEDED") {
            const actions = element("div", "report-dimension-list");
            if (!advice.actions.length) actions.append(element("p", "report-empty", "暂无"));
            advice.actions.forEach((action, index) => actions.append(adviceItem(context, (index + 1) + ". " + action.title,
                categoryLabels[action.category] + " · " + evidenceStatusLabels[action.evidenceStatus], action.content, action.issueIds)));
            panel.append(section("优化建议（按优先级）", "由模型根据下方规则发现的问题归纳；“待验证推测”表示原因或做法是推断，需要先核实。", actions));
            const findings = element("div", "report-dimension-list");
            if (!advice.findings.length) findings.append(element("p", "report-empty", "暂无"));
            for (const finding of advice.findings) findings.append(adviceItem(context, finding.content, null, null, finding.issueIds));
            panel.append(section("主要发现", null, findings));
        } else {
            panel.append(element("div", "report-progress", advice.status === "NO_ISSUES" ? "规则没有发现需要处理的问题，未生成建议。" : advice.errorMessage));
        }
        const issues = element("div", "report-dimension-list");
        // 筛选模型时只列该模型的问题；不属于单个模型的问题（channelId 为空）始终列出
        const visibleIssues = summary.issues.filter(issue => !context.modelFilter || issue.channelId == null || String(issue.channelId) === context.modelFilter);
        if (!visibleIssues.length) issues.append(element("p", "report-empty", "暂无"));
        for (const issue of visibleIssues) issues.append(adviceItem(context, issue.id + " " + issue.title, metricsText(issue.metrics), null, [issue.id]));
        panel.append(section("规则发现的问题", "由程序按固定阈值从 ②–⑤ 的结果中挑出，不经过模型；建议只能引用这里的编号。", issues));
        panel.append(section("复测方式", null, element("p", "help",
            "按建议调整后，用同一诊断方案（原问题、原渠道配置）重新执行诊断任务并生成报告；判定模型与提示词版本一致时，再与本报告对比。")));
    }

    /**
     * ⑦ 地域报告：按问题的地域分子页签，每个地域给出宣传状态、大模型可见度、机会与提升策略。
     * 数据：summary.region，由 ReportRegionSummarizer 生成；各地域的 strategy 由 RegionStrategyGenerator 调用模型写入。
     * 旧报告没有 region，问题都不分地域时 regions 为空，两种情况都只显示说明。
     * @param {object} context 模型筛选与查看入口，见 renderTab
     * @param {HTMLElement} panel 内容写入的容器
     * @param {object} summary 报告汇总
     */
    function renderRegion(context, panel, summary) {
        const report = summary.region;
        if (!report) {
            panel.append(element("div", "report-progress", "本报告没有地域数据。给方案的问题设置地域并重新发起诊断后，新生成的报告才有地域报告。"));
            return;
        }
        if (!report.regions.length) {
            panel.append(element("div", "report-progress", "本次诊断的问题都没有设置地域，暂无地域报告。"));
            return;
        }
        if (report.unassignedAnswers) panel.append(element("p", "report-filter-note", report.unassignedAnswers + " 条回答的问题不分地域，未计入地域报告。"));
        // 子页签是链接，默认显示第一个地域，切换由页面与导出网页各自的脚本完成
        const group = element("div", "report-subtab-group"); group.dataset.subtabGroup = "region";
        const bar = element("div", "report-subtabs"); bar.setAttribute("role", "tablist");
        group.append(bar);
        report.regions.forEach((region, index) => {
            const tab = element("a", "report-subtab", region.name);
            tab.href = "#"; tab.dataset.subtab = String(index);
            tab.setAttribute("role", "tab"); tab.setAttribute("aria-selected", String(index === 0));
            bar.append(tab);
            const content = element("div"); content.dataset.subtabPanel = String(index); content.hidden = index > 0;
            renderRegionContent(context, content, region);
            group.append(content);
        });
        panel.append(group);
    }

    /**
     * 一个地域的四部分内容：宣传状态、大模型可见度、机会、提升策略。
     * @param {object} context 模型筛选与查看入口，见 renderTab
     * @param {HTMLElement} container 内容写入的子页签容器
     * @param {object} region 一个地域：name、sample、portrayal、visibility、opportunities、strategy
     */
    function renderRegionContent(context, container, region) {
        const sample = region.sample;
        container.append(element("p", "help report-region-sample", region.name + " · 有效回答 " + sample.validAnswers + " 条（判定成功 " + sample.judged
            + " 条）· 题数 " + sample.questions + " · 模型数 " + sample.models));
        container.append(section("1 宣传状态", "计入有态度的回答：点名题里有品牌情感的，以及其他题里提到目标且有推荐强度的；首推、强推算正面，明确不推荐算负面，其余算中性。"
            + "正面 ≥ 50% 且负面 < 20% 为正向，负面 ≥ 30% 为负向，少于 3 条为证据不足。", renderPortrayal(context, region.portrayal)));
        container.append(section("2 大模型可见度（非品牌问题）", "口径与 ① ④ 相同，范围只含该地域的问题。", renderRegionVisibility(context, region.visibility)));
        const opportunities = element("div", "report-dimension-list");
        if (!region.opportunities.length) opportunities.append(element("p", "report-empty", "暂无"));
        for (const item of region.opportunities) {
            opportunities.append(adviceItem(context, item.id + " " + item.title, (opportunityLabels[item.type] || item.type) + " · " + metricsText(item.metrics), null, [item.id]));
        }
        container.append(section("3 机会", "由程序按固定规则从该地域的回答中挑出，不经过模型；提升策略只能引用这里的编号。", opportunities));
        container.append(section("4 提升策略", "由模型根据上面的数据和机会归纳；「待验证推测」表示对当地市场、平台、渠道的判断是推断，需要先核实。",
            renderRegionStrategy(context, region.strategy)));
    }

    /**
     * 地域的宣传状态：结论、正中负回答数，以及正面、负面证据。
     * @param {object} context 模型筛选与查看入口，见 renderTab
     * @param {object} portrayal 宣传状态：status、正中负计数与占比、各维度排行、factErrors、negativeSources
     * @return {HTMLElement} 宣传状态内容
     */
    function renderPortrayal(context, portrayal) {
        const box = element("div");
        const line = element("p", "report-portrayal");
        line.append(element("strong", "report-portrayal-badge report-portrayal-" + portrayal.status.toLowerCase(), portrayalLabels[portrayal.status] || portrayal.status),
            element("span", null, "正面 " + portrayal.positive + " · 中性 " + portrayal.neutral + " · 负面 " + portrayal.negative
                + " 条（正面占比 " + percent(portrayal.positiveRate) + "，负面占比 " + percent(portrayal.negativeRate) + "）"));
        box.append(line);
        const positives = element("div", "report-dimensions report-dimensions-2");
        positives.append(dimensionList(context, "正面评价", "点名问题中对目标的正面评价", portrayal.positives),
            dimensionList(context, "推荐理由", "推荐目标时给出的理由", portrayal.recommendReasons));
        const negatives = element("div", "report-dimensions report-dimensions-2");
        negatives.append(dimensionList(context, "负面评价", "点名问题中对目标的负面评价", portrayal.negatives),
            dimensionList(context, "保留意见", "推荐目标时附带的缺点", portrayal.reservations));
        box.append(element("h4", null, "正面证据"), positives, element("h4", null, "负面证据"), negatives);
        box.append(element("h4", null, "错误信息"), quoteList(context, ofModel(context, portrayal.factErrors).map(error => ({
            title:error.content, quote:error.quote, channelName:error.channelName, resultId:error.resultId}))));
        box.append(element("h4", null, "负面回答引用的平台"), table(["平台", "引用次数", "链接示例"],
            portrayal.negativeSources.map(row => [row.platform, row.count, row.links.map(link => link.title || link.url).join("；")]), 1));
        return box;
    }

    /**
     * 地域的大模型可见度：各模型核心指标，以及目标与预设竞品的对比。
     * @param {object} context 模型筛选与查看入口，见 renderTab
     * @param {object} visibility 可见度：models、brands
     * @return {HTMLElement} 可见度内容
     */
    function renderRegionVisibility(context, visibility) {
        const box = element("div");
        box.append(table(["模型", "非品牌回答", "提及率", "推荐率", "首推率", "平均排名", "问题覆盖率", "稳定性"], ofModel(context, visibility.models).map(model => [
            model.channelName + " · " + model.modelName, model.nonBrandAnswers, percent(model.mentionRate), percent(model.recommendRate),
            percent(model.firstChoiceRate), model.averageRank ?? "—", percent(model.questionCoverage), percent(model.stability)])));
        box.append(table(["模型", "品牌", "提及率", "推荐率", "首推率"], ofModel(context, visibility.brands).map(row => [
            row.channelName + " · " + row.modelName, row.brandName + (row.target ? "（目标）" : ""),
            percent(row.mentionRate), percent(row.recommendRate), percent(row.firstChoiceRate)]), 2));
        return box;
    }

    /**
     * 地域的提升策略：品牌优化方向、从未点名到正向点名、已正向点名提升排名，每条有操作路径和「看证据」。
     * @param {object} context 模型筛选与查看入口，见 renderTab
     * @param {object} strategy 提升策略：status 为 SUCCEEDED、NO_OPPORTUNITIES 或 FAILED
     * @return {HTMLElement} 提升策略内容
     */
    function renderRegionStrategy(context, strategy) {
        if (strategy.status === "NO_OPPORTUNITIES") return element("p", "report-empty", "该地域没有发现机会，未生成策略。");
        if (strategy.status === "FAILED") return element("p", "report-empty", strategy.errorMessage);
        const box = element("div", "report-strategy");
        for (const [key, title, help] of [["brandDirections", "品牌优化方向", "结合当地用户习惯、常用平台和购买渠道的品牌优化方向"],
            ["mentionPlays", "从未点名到正向点名", "目标没被提到或没被推荐的题，怎么让模型正向点名"],
            ["rankPlays", "已正向点名：提升排名", "目标已被推荐但不是首推的题，怎么往前排"]]) {
            const list = element("div", "report-dimension-list");
            list.append(element("h4", null, title), element("p", "help", help));
            if (!strategy[key].length) list.append(element("p", "report-empty", "暂无"));
            for (const item of strategy[key]) list.append(strategyItem(context, item));
            box.append(list);
        }
        return box;
    }

    /**
     * 一条策略：标题、证据状态、说明、操作步骤，引用了机会时附「看证据」。
     * @param {object} context 模型筛选与查看入口，见 renderTab
     * @param {{title:string, content:string, steps:string[], evidenceStatus:string, opportunityIds:string[]}} item 策略条目
     * @return {HTMLElement} 一条策略
     */
    function strategyItem(context, item) {
        const node = element("div", "report-dimension report-advice-item");
        node.append(element("strong", null, item.title), element("span", "report-advice-meta", evidenceStatusLabels[item.evidenceStatus] || item.evidenceStatus));
        if (item.content) node.append(element("p", "report-advice-content", item.content));
        if (item.steps.length) {
            const steps = element("ol", "report-steps");
            for (const step of item.steps) steps.append(element("li", null, step));
            node.append(steps);
        }
        if (item.opportunityIds.length) {
            const line = element("p", "report-quote");
            line.append(context.entries.evidence(item.opportunityIds));
            node.append(line);
        }
        return node;
    }

    /**
     * 切换子页签：只显示同组里所选的内容；页面上点子页签时调用，导出网页里由导出脚本做同样的事。
     * 子页签组可以套在另一组里，只处理直接属于这一组的子页签和内容，不动里层那组。
     * @param {HTMLElement} group 带 data-subtab-group 的子页签组
     * @param {string} key 所选子页签的 data-subtab 值；组里没有这个值时显示第一个
     */
    function selectSubtab(group, key) {
        const own = selector => [...group.querySelectorAll(selector)].filter(node => node.closest("[data-subtab-group]") === group);
        const subtabs = own("[data-subtab]");
        const selected = subtabs.some(tab => tab.dataset.subtab === key) ? key : subtabs[0]?.dataset.subtab;
        subtabs.forEach(tab => tab.setAttribute("aria-selected", String(tab.dataset.subtab === selected)));
        own("[data-subtab-panel]").forEach(content => { content.hidden = content.dataset.subtabPanel !== selected; });
    }

    /**
     * 建议、发现或问题的一行：标题、补充说明、正文，以及「看证据」入口。
     * @param {object} context 模型筛选与查看入口，见 renderTab
     * @param {string} title 标题
     * @param {string|null} meta 标题后的补充说明，没有时为 null
     * @param {string|null} content 正文，没有时为 null
     * @param {string[]} issueIds 支撑这条内容的问题编号
     * @return {HTMLElement} 一行内容
     */
    function adviceItem(context, title, meta, content, issueIds) {
        const item = element("div", "report-dimension report-advice-item");
        item.append(element("strong", null, title));
        if (meta) item.append(element("span", "report-advice-meta", meta));
        if (content) item.append(element("p", "report-advice-content", content));
        const line = element("p", "report-quote");
        line.append(context.entries.evidence(issueIds));
        item.append(line);
        return item;
    }

    /**
     * @param {{label:string, value:number, rate:boolean}[]} metrics 问题的支撑数字
     * @return {string} 支撑数字的一行文字，比率按百分比展示
     */
    function metricsText(metrics) {
        return metrics.map(metric => metric.label + " " + (metric.rate ? percent(metric.value) : metric.value)).join(" · ");
    }

    /**
     * 逐个问题或机会写出编号标题、支撑数字和证据；系统中的证据弹窗与导出网页中的证据弹窗共用。
     * 编号可能是 ⑥ 规则问题（P1…），也可能是 ⑦ 各地域的机会（R1-O1…），两者结构相同、编号不重复。
     * @param {object} context 模型筛选与查看入口，见 renderTab
     * @param {HTMLElement} container 写入的容器；加上证据样式类，两处证据弹窗的标题样式一致
     * @param {object} summary 证据所属报告的冻结汇总
     * @param {string[]} issueIds 问题或机会编号，按报告中的先后输出
     */
    function renderEvidence(context, container, summary, issueIds) {
        container.classList.add("report-evidence");
        const items = [...(summary.issues || []), ...(summary.region?.regions || []).flatMap(region => region.opportunities)];
        for (const issue of items.filter(item => issueIds.includes(item.id))) {
            container.append(element("h4", null, issue.id + " " + issue.title), element("p", "help", metricsText(issue.metrics)));
            if (!issue.evidence.length) container.append(element("p", "report-empty", "此问题由上面的数字支撑，没有对应的回答原文。"));
            for (const item of issue.evidence) container.append(evidenceLine(context, item));
        }
    }

    /**
     * 一条证据：回答原文、引用的来源链接或问题原文，都带「看回答」入口。
     * @param {object} context 模型筛选与查看入口，见 renderTab
     * @param {{resultId:number, channelName:string, brand:?string, quote:?string, url:?string, title:?string, question:?string}} item 证据
     * @return {HTMLElement} 一行内容
     */
    function evidenceLine(context, item) {
        const from = item.channelName + (item.brand ? " · " + item.brand : "");
        if (item.quote != null) return quoteLine(context, item.quote, from, item.resultId);
        if (item.url != null) return answerLine(context, "来源：" + (item.title || item.url) + "（" + item.url + "）— " + from, item.resultId);
        return answerLine(context, "问题：" + item.question + " — " + from, item.resultId);
    }

    /**
     * 维度排行：每个维度一行，附回答原文示例；示例中有品牌时一并展示。
     * @param {object} context 模型筛选与查看入口，见 renderTab
     * @param {string} title 标题
     * @param {string} help 标题下的说明
     * @param {object[]} rows 维度排行，每行含 dimension、examples 及计数
     * @param {function(object):string} describe 每行右侧的计数写法，默认“N 次”
     * @return {HTMLElement} 维度排行卡片
     */
    function dimensionList(context, title, help, rows, describe = row => row.count + " 次") {
        const box = element("div", "report-dimension-list");
        box.append(element("h4", null, title), element("p", "help", help));
        if (!rows.length) box.append(element("p", "report-empty", "暂无"));
        for (const row of rows) {
            const item = element("div", "report-dimension");
            item.append(element("strong", null, row.dimension), element("span", "count", describe(row)));
            for (const example of row.examples) item.append(quoteLine(context, example.quote, example.channelName + (example.brand ? " · " + example.brand : ""), example.resultId));
            box.append(item);
        }
        return box;
    }

    /**
     * 逐条清单（如错误信息）：每条一个标题加一句原文。
     * @param {object} context 模型筛选与查看入口，见 renderTab
     * @param {{title:string, quote:string, channelName:string, resultId:number}[]} rows 清单
     * @return {HTMLElement} 清单卡片
     */
    function quoteList(context, rows) {
        const box = element("div", "report-dimension-list");
        if (!rows.length) box.append(element("p", "report-empty", "暂无"));
        for (const row of rows) {
            const item = element("div", "report-dimension");
            item.append(element("strong", null, row.title || "—"), quoteLine(context, row.quote, row.channelName, row.resultId));
            box.append(item);
        }
        return box;
    }

    /**
     * 一句回答原文加来源，以及「看回答」入口。
     * @param {object} context 模型筛选与查看入口，见 renderTab
     * @param {string} quote 回答原文
     * @param {string} from 来源（模型，有品牌时带品牌）
     * @param {number} resultId 诊断结果 ID
     * @return {HTMLElement} 一行内容
     */
    function quoteLine(context, quote, from, resultId) {
        return answerLine(context, "“" + quote + "” — " + from, resultId);
    }

    /**
     * 一行说明文字加「看回答」入口。
     * @param {object} context 模型筛选与查看入口，见 renderTab
     * @param {string} text 说明文字
     * @param {number} resultId 诊断结果 ID
     * @return {HTMLElement} 一行内容
     */
    function answerLine(context, text, resultId) {
        const line = element("p", "report-quote", text);
        line.append(context.entries.answer(resultId));
        return line;
    }

    /**
     * 带标题和说明的一段内容。
     * @param {string} title 标题
     * @param {string|null} help 标题下的说明，没有时为 null
     * @param {...HTMLElement} children 正文
     * @return {HTMLElement} 段落
     */
    function section(title, help, ...children) {
        const node = element("section", "report-section");
        node.append(element("h3", null, title));
        if (help) node.append(element("p", "help", help));
        node.append(...children);
        return node;
    }

    /**
     * 数据表格；单元格统一用文本节点写入，模型原文不作为 HTML 解析。
     * @param {string[]} headers 表头
     * @param {Array<Array<string|number>>} rows 每行的单元格
     * @param {number} metricFrom 从第几列（0 起）开始是数字，数字列右对齐，默认第 1 列
     * @return {HTMLElement} 可横向滚动的表格
     */
    function table(headers, rows, metricFrom = 1) {
        const wrapper = element("div", "table-scroll"), tableNode = element("table", "report-table");
        const head = tableNode.createTHead().insertRow();
        headers.forEach((text, index) => {
            const cell = element("th", index >= metricFrom ? "metric" : null, text);
            cell.scope = "col";
            head.append(cell);
        });
        const body = tableNode.createTBody();
        if (!rows.length) {
            const cell = body.insertRow().insertCell();
            cell.colSpan = headers.length;
            cell.className = "table-message";
            cell.textContent = "暂无数据";
        }
        for (const values of rows) {
            const row = body.insertRow();
            values.forEach((value, index) => {
                const cell = row.insertCell();
                if (index >= metricFrom) cell.className = "metric";
                cell.textContent = value;
            });
        }
        wrapper.append(tableNode);
        return wrapper;
    }

    /**
     * 按模型筛选：只作用于按模型分行的表格，排行与清单是全部模型合计。
     * @param {object} context 模型筛选与查看入口，见 renderTab
     * @param {object[]} rows 按模型分行的数据，每行带 channelId
     * @return {object[]} 筛选后的行；未筛选时原样返回
     */
    function ofModel(context, rows) {
        return context.modelFilter ? rows.filter(row => String(row.channelId) === context.modelFilter) : rows;
    }

    window.DiagnosisReportView = Object.freeze({statusLabels, stageLabels, tabs, buildReportNote, buildModelFilterNote, renderTab, renderEvidence, selectSubtab});
})();
