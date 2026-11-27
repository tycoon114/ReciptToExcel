"use strict";
window.MonthlyResults = (() => {
  const el = id => document.getElementById(id);
  const monthInput = el("results-month");
  const now = new Date();
  let documents = [], revision = 0, exporting = false;

  function setMonth(year, month) {
    monthInput.value = `${year}-${String(month).padStart(2, "0")}`;
    render();
  }
  function render() {
    const match = /^(\d{4})-(\d{2})$/.exec(monthInput.value);
    const valid = match && monthInput.validity.valid;
    el("results-period").textContent = valid ? `${Number(match[1])}년 ${Number(match[2])}월` : "조회할 월을 선택해 주세요.";
    el("results-previous").disabled = !valid || monthInput.value === "1900-01";
    el("results-next").disabled = !valid || monthInput.value === "9999-12";
    el("results-export").disabled = exporting || !documents.some(document => document.analysis.analyzedAt)
      || (el("results-export-scope").value === "month" && !valid);
    const kindRows = kind => documents.filter(document => document.file.kind === kind && document.analysis.analyzedAt)
      .flatMap(document => document.analysis.rows).filter(row => valid && row.date.startsWith(monthInput.value + "-"));
    function amount(id, kind, key) {
      const analyzed = documents.some(document => document.file.kind === kind && document.analysis.rows.length > 0);
      const selected = kindRows(kind);
      const rows = selected.filter(row => row.currency === "KRW");
      const knownCurrency = selected.length === 0 || rows.length > 0;
      el(id).textContent = valid && analyzed && knownCurrency ? rows.reduce((sum, row) => sum + (row[key] ?? 0), 0).toLocaleString("ko-KR") + "원" : "—";
    }
    amount("results-card-total", "CARD_PDF", "amount");
    amount("results-deposit-total", "ACCOUNT_PDF", "deposit");
    amount("results-withdrawal-total", "ACCOUNT_PDF", "withdrawal");
    const invoices = kindRows("TAX_INVOICE");
    el("results-invoice-count").textContent = valid && documents.some(document => document.file.kind === "TAX_INVOICE" && document.analysis.rows.length > 0)
      ? `매출 ${invoices.filter(row => row.type === "매출").length}건 · 매입 ${invoices.filter(row => row.type === "매입").length}건 · 미지정 ${invoices.filter(row => !["매출", "매입"].includes(row.type)).length}건` : "—";
    const detail = el("results-analysis-detail"); detail.replaceChildren();
    for (const document of documents) {
      for (const issue of document.analysis.issues) {
        const item = document.createElement("li");
        item.textContent = document.file.name + (issue.page ? ` · ${issue.page}쪽` : "") + " · " + issue.message;
        detail.append(item);
      }
    }
    el("results-review-empty").hidden = detail.childElementCount > 0;
    el("results-review-empty").textContent = documents.some(document => document.analysis.analyzedAt)
      ? "등록 자료에서 보고된 추출 오류가 없습니다. 원본과 추출 내용을 확인해 주세요."
      : "자료 가져오기에서 ‘등록 자료 분석’을 실행해 주세요.";
  }
  async function refresh() {
    const current = ++revision;
    try {
      const result = await api("/api/imports/analysis");
      if (current !== revision) return;
      documents = result; render(); el("results-error").hidden = true;
    } catch (error) {
      if (current !== revision) return;
      documents = []; render();
      el("results-error").textContent = "분석 결과를 불러오지 못했습니다. " + error.message;
      el("results-error").hidden = false;
    }
  }
  async function exportWorkbook() {
    if (exporting || el("results-export").disabled) return;
    exporting = true; render(); el("results-export").textContent = "엑셀 만드는 중…";
    try {
      const month = el("results-export-scope").value === "month" ? monthInput.value : "";
      const response = await fetch("/api/imports/export.xlsx" + (month ? "?month=" + encodeURIComponent(month) : ""));
      if (!response.ok) throw new Error("엑셀 생성에 실패했습니다. 다시 시도해 주세요.");
      const blob = await response.blob(), url = URL.createObjectURL(blob);
      const link = document.createElement("a"); link.href = url; link.download = "추출내역_" + (month || "전체") + ".xlsx";
      document.body.append(link); link.click(); link.remove();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
      el("results-error").hidden = true;
    } catch (error) { el("results-error").textContent = error.message; el("results-error").hidden = false; }
    finally { exporting = false; el("results-export").textContent = "엑셀 만들기"; render(); }
  }
  function moveMonth(offset) {
    if (!monthInput.value || !monthInput.validity.valid) return;
    const [year, month] = monthInput.value.split("-").map(Number);
    const date = new Date(year, month - 1 + offset, 1);
    setMonth(date.getFullYear(), date.getMonth() + 1);
  }
  function init() {
    setMonth(now.getFullYear(), now.getMonth() + 1);
    monthInput.addEventListener("change", render);
    el("results-export-scope").addEventListener("change", render);
    el("results-export").addEventListener("click", exportWorkbook);
    el("results-previous").addEventListener("click", () => moveMonth(-1));
    el("results-next").addEventListener("click", () => moveMonth(1));
    el("results-current").addEventListener("click", () => setMonth(now.getFullYear(), now.getMonth() + 1));
    el("results-import").addEventListener("click", () => ImportHome.showView("imports"));
    window.addEventListener("imports-changed", refresh);
    window.addEventListener("workspace-view", event => { if (event.detail === "monthly") refresh(); });
    refresh();
  }
  return { init };
})();
MonthlyResults.init();
