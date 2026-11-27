"use strict";
window.MonthlyResults = (() => {
  const el = id => document.getElementById(id);
  const monthInput = el("results-month");
  const now = new Date();
  let documents = [], revision = 0, exporting = false, reviewing = false;

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
    el("results-test-lookup").disabled=reviewing || !documents.some(sourceDocument=>sourceDocument.file.kind==="CARD_PDF" && sourceDocument.analysis.rows.length>0);
    const monthlyExport=el("results-export-scope").value==="month";
    const hasExportRows=documents.some(sourceDocument=>sourceDocument.file.kind!=="RECEIPT_PHOTO" &&
      sourceDocument.analysis.rows.some(row=>!monthlyExport || (valid && row.date.startsWith(monthInput.value+"-"))));
    el("results-export").disabled=exporting || reviewing || !hasExportRows || (monthlyExport && !valid);
    el("results-export-help").textContent=monthlyExport && !valid?"조회할 월을 선택해 주세요.":!hasExportRows?
      (monthlyExport?"선택한 월에 내보낼 내역이 없습니다.":"내보낼 추출 내역이 없습니다. 등록 자료를 분석해 주세요."):
      "일부 자료가 없어도 다운로드할 수 있습니다. 통합내역과 자료별 시트를 함께 저장합니다.";
    renderRegistration(valid);
    const kindRows = kind => documents.filter(sourceDocument=> sourceDocument.file.kind === kind && sourceDocument.analysis.analyzedAt)
      .flatMap(sourceDocument=> sourceDocument.analysis.rows).filter(row => valid && row.date.startsWith(monthInput.value + "-"));
    function amount(id, kind, key) {
      const analyzed = documents.some(sourceDocument=> sourceDocument.file.kind === kind && sourceDocument.analysis.rows.length > 0);
      const selected = kindRows(kind);
      const rows = selected.filter(row => row.currency === "KRW");
      const knownCurrency = selected.length === 0 || rows.length > 0;
      el(id).textContent = valid && analyzed && knownCurrency ? rows.reduce((sum, row) => sum + (row[key] ?? 0), 0).toLocaleString("ko-KR") + "원" : "—";
    }
    amount("results-card-total", "CARD_PDF", "amount");
    amount("results-deposit-total", "ACCOUNT_PDF", "deposit");
    amount("results-withdrawal-total", "ACCOUNT_PDF", "withdrawal");
    const invoices = kindRows("TAX_INVOICE");
    el("results-invoice-count").textContent = valid && documents.some(sourceDocument=> sourceDocument.file.kind === "TAX_INVOICE" && sourceDocument.analysis.rows.length > 0)
      ? `매출 ${invoices.filter(row => row.type === "매출").length}건 · 매입 ${invoices.filter(row => row.type === "매입").length}건 · 미지정 ${invoices.filter(row => !["매출", "매입"].includes(row.type)).length}건` : "—";
    const cardReviews=[],cardItems=[];
    for(const sourceDocument of documents.filter(sourceDocument=>sourceDocument.file.kind==="CARD_PDF")){
      const taxes=new Map((sourceDocument.analysis.cardReviews || []).map(tax=>[tax.page+"/"+tax.line+"/"+(tax.sheet || ""),tax]));
      for(const row of sourceDocument.analysis.rows){
        if(!valid || !row.date.startsWith(monthInput.value+"-"))continue;
        const tax=taxes.get(row.page+"/"+row.line+"/"+(row.sheet || ""));
        const item={tax,row,file:sourceDocument.file.name};cardItems.push(item);
        if(tax)cardReviews.push(item);
      }
    }
    const money=value=>value==null?"미기재":value.toLocaleString("ko-KR")+"원";
    const missingVat=cardItems.filter(item=>item.row.vat==null).length;
    const hasCards=documents.some(sourceDocument=>sourceDocument.file.kind==="CARD_PDF" && sourceDocument.analysis.rows.length>0);
    el("results-vat-total").textContent=valid && hasCards?cardItems.reduce((sum,item)=>sum+(item.row.vat ?? 0),0).toLocaleString("ko-KR")+"원":"—";
    el("results-vat-total-note").textContent=missingVat?`세액 미기재 ${missingVat}건 · 기재된 세액만 합산`:"원본 기재 세액 합계 · 취소·확인 필요 포함";
    const body=el("results-vat-rows");body.replaceChildren();
    cardItems.sort((a,b)=>a.row.date.localeCompare(b.row.date));
    for(const item of cardItems.slice(0,200)){
      const tax=item.tax,row=item.row,tr=document.createElement("tr");
      const values=[row.date,row.party,tax?.lookup?.taxType || "미조회",money(row.vat),tax?.candidateVat==null?"—":money(tax.candidateVat),
        tax?.review?.label || "미조회",tax?.review?.reason || "카드 과세유형·부가세 검토를 실행해 주세요.",tax?(tax.mode==="TEST"?"테스트 조회":"국세청 실제 조회"):"미조회"];
      values.forEach(value=>{const cell=document.createElement("td");cell.textContent=value;tr.append(cell);});body.append(tr);
    }
    el("results-vat-empty").hidden=cardItems.length>0;
    el("results-vat-count").textContent=cardItems.length>200?`${cardItems.length}건 중 200건 표시 · 전체는 엑셀에서 확인`:`${cardItems.length}건`;
    const tested=cardReviews.filter(item=>item.tax.mode==="TEST").length;
    el("results-vat-source").textContent=cardReviews.length?`테스트 조회 ${tested}건 · 국세청 실제 조회 ${cardReviews.length-tested}건 · 카드 내역 기준`:
      "자료 가져오기에서 카드 과세유형·부가세 검토를 실행하세요. 테스트 조회는 가상 응답을 사용합니다.";
    el("results-vat-candidate").textContent=cardReviews.length?cardReviews.reduce((sum,item)=>sum+(item.tax.candidateVat ?? 0),0).toLocaleString("ko-KR")+"원":"—";
    const excluded=cardReviews.filter(item=>item.tax.review.code==="NON_DEDUCTIBLE");
    el("results-vat-excluded").textContent=cardReviews.length?(excluded.some(item=>item.row.vat==null)?"세액 미확인 포함":excluded.reduce((sum,item)=>sum+(item.row.vat ?? 0),0).toLocaleString("ko-KR")+"원"):"—";
    const cardCount=kindRows("CARD_PDF").length;
    el("results-vat-pending").textContent=cardCount?((cardCount-cardReviews.length)+cardReviews.filter(item=>item.tax.review.code==="REVIEW").length)+"건":"—";
    const detail = el("results-analysis-detail"); detail.replaceChildren();
    for (const sourceDocument of documents) {
      for (const issue of sourceDocument.analysis.issues) {
        const item = document.createElement("li");
        item.textContent = sourceDocument.file.name + (issue.sheet ? ` · ${issue.sheet}` : issue.page ? ` · ${issue.page}쪽` : "") + " · " + issue.message;
        detail.append(item);
      }
    }
    for(const item of cardReviews.filter(item=>item.tax.review.code==="REVIEW")){
      const line=document.createElement("li");
      line.textContent=`${item.file} · ${item.row.date} · ${item.row.party} · ${item.tax.mode==="TEST"?"테스트 조회":"실제 조회"} · ${item.tax.review.reason}`;
      detail.append(line);
    }
    el("results-review-empty").hidden = detail.childElementCount > 0;
    el("results-review-empty").textContent = documents.some(sourceDocument=> sourceDocument.analysis.analyzedAt)
      ? "등록 자료에서 보고된 추출 오류가 없습니다. 원본과 추출 내용을 확인해 주세요."
      : "자료 가져오기에서 ‘등록 자료 분석’을 실행해 주세요.";
  }
  function renderRegistration(valid){
    const body=el("results-registration-rows");body.replaceChildren();
    for(const [kind,label] of [["CARD_PDF","법인카드"],["ACCOUNT_PDF","계좌 입출금"],["TAX_INVOICE","세금계산서"],["RECEIPT_PHOTO","영수증 사진"]]){
      const files=documents.filter(sourceDocument=>sourceDocument.file.kind===kind);
      const selected=files.map(sourceDocument=>({sourceDocument,rows:sourceDocument.analysis.rows.filter(row=>valid && row.date.startsWith(monthInput.value+"-"))}));
      const count=selected.reduce((sum,item)=>sum+item.rows.length,0);
      const photo=kind==="RECEIPT_PHOTO";
      const unassigned=files.filter(sourceDocument=>!sourceDocument.analysis.analyzedAt ||
        sourceDocument.analysis.status!=="READY" || sourceDocument.analysis.rows.some(row=>!row.date)).length;
      const state=!files.length?"등록 자료 없음":photo?"등록됨 · 월 연결 전":!valid?"월 선택 필요":count?
        `${count}건 · 해당 파일 ${selected.filter(item=>item.rows.length).length}개`:"선택 월 추출 내역 없음";
      const note=photo?"선택 자료 · 전체 등록 수 · 거래일 연결 전":unassigned?
        `전체 등록 중 분석 대기·확인 필요 ${unassigned}개 · 월 귀속 확인 필요`:"추출된 거래일·작성일 기준";
      const tr=document.createElement("tr");
      for(const value of [label,files.length+"개",state,note]){
        const td=document.createElement("td");td.textContent=value;tr.append(td);
      }
      body.append(tr);
    }
  }
  async function refresh() {
    if(reviewing)return;
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
  async function applyTestLookup(){
    if(reviewing || el("results-test-lookup").disabled)return;
    reviewing=true;render();el("results-test-lookup").textContent="테스트 조회 적용 중…";
    const current=++revision;
    try{
      const result=await api("/api/imports/card-vat?mode=TEST",{method:"POST"});
      if(current!==revision)return;
      documents=result;
      const months=[...new Set(documents.filter(sourceDocument=>sourceDocument.file.kind==="CARD_PDF")
        .flatMap(sourceDocument=>sourceDocument.analysis.rows).map(row=>row.date.slice(0,7))
        .filter(month=>/^\d{4}-\d{2}$/.test(month)))].sort().reverse();
      if(months.length && !months.includes(monthInput.value))monthInput.value=months[0];
      render();el("results-error").hidden=true;
    }catch(error){
      if(current===revision){el("results-error").textContent=error.message;el("results-error").hidden=false;}
    }finally{reviewing=false;el("results-test-lookup").textContent="테스트 조회 적용";render();await refresh();}
  }
  async function exportWorkbook() {
    if (exporting || el("results-export").disabled) return;
    exporting = true; render(); el("results-export").textContent = "엑셀 만드는 중…";
    try {
      const month = el("results-export-scope").value === "month" ? monthInput.value : "";
      const path="/api/imports/export.xlsx";
      const response = await fetch(path + (month ? "?month=" + encodeURIComponent(month) : ""));
      if (!response.ok) {
        const error=await response.json().catch(()=>({}));
        throw new Error(error.message || "엑셀 생성에 실패했습니다. 다시 시도해 주세요.");
      }
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
    el("results-test-lookup").addEventListener("click",applyTestLookup);
    el("results-export-scope").addEventListener("change", render);
    el("results-export").addEventListener("click", exportWorkbook);
    el("results-previous").addEventListener("click", () => moveMonth(-1));
    el("results-next").addEventListener("click", () => moveMonth(1));
    el("results-current").addEventListener("click", () => setMonth(now.getFullYear(), now.getMonth() + 1));
    el("results-import").addEventListener("click", () => ImportHome.showView("imports"));
    window.addEventListener("imports-changed", event => {
      if(event.detail?.deletedId){
        ++revision;
        documents=documents.filter(sourceDocument=>sourceDocument.file.id!==event.detail.deletedId);
        render();
      }
      refresh();
    });
    window.addEventListener("workspace-view", event => { if (event.detail === "monthly") refresh(); });
    refresh();
  }
  return { init };
})();
MonthlyResults.init();
