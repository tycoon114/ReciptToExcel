"use strict";

// 중복 의심을 회계 확정 상태와 분리해 보여 주고, 두 증빙을 비교한 판단만 서버로 전달합니다.
const DuplicateUI = (() => {
  let source=null, busy=false, revision=0;
  const el=id=>document.getElementById(id);
  function node(tag,text,className) {
    const value=document.createElement(tag);if(text!=null)value.textContent=text;if(className)value.className=className;return value;
  }
  const label=e=>e.duplicateState==="EXCLUDED"?"중복 제외":e.status==="CONFIRMED"?"확정된 내역":"검토 대기";
  function evidence(expense,title) {
    const box=node("div",null,"duplicate-evidence");
    box.append(node("strong",title),node("p",`#${expense.id} · ${label(expense)} · ${expense.originalName||"직접 입력"}`),
      node("p",`${expense.receiptDate||"날짜 미확인"} / ${expense.vendor||"거래처 미확인"} / ${money(expense.amount)}원`),
      node("p",`승인번호: ${expense.approvalNumber||"없음·미확인"}`),
      node("p",`사업자번호: ${expense.businessNumber||"미확인"} / 거래 시간: ${expense.receiptTime||"미확인"}`),
      node("p",`거래처 주소: ${expense.vendorAddress||"미확인"}`));
    if(expense.imagePath){
      const link=node("a");link.href=`/api/expenses/${expense.id}/image?v=${expense.version}`;link.target="_blank";link.rel="noopener";
      const image=node("img");image.src=link.href;image.alt=`${title} 영수증 원본`;image.loading="lazy";link.append(image);box.append(link);
    }
    return box;
  }
  function showError(message){el("duplicate-error").textContent=message;el("duplicate-error").hidden=false;}
  function hasDraft(){return fields.some(field=>{
    const value=el("edit-"+field).value.trim();
    return ["amount","supplyAmount","vatAmount"].includes(field)?(value===""?null:Number(value))!==(source[field]??null):value!==String(source[field]??"").trim();
  });}
  async function decide(match,decision){
    if(busy || photoBusy || uploading || BookkeepingUI.isBusy())return;
    if(hasDraft()){showError("수정한 내용은 먼저 ‘검토 대기로 저장’한 뒤 다시 열어 비교해 주세요. 중복 판단은 저장된 값이 기준입니다.");return;}
    if(!confirm(decision==="EXCLUDE"?"이번 등록 건을 중복으로 제외할까요? 기존 내역은 그대로 두고 이번 증빙도 보관합니다.":"두 증빙을 비교했으며 서로 다른 거래가 맞나요? 이 비교 대상에 대한 판단을 저장합니다."))return;
    busy=true;applyLocks();el("duplicate-error").hidden=true;
    try{
      const expense=await api(`/api/expenses/${source.id}/duplicate-review`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({
        version:source.version,candidateId:match.expense.id,candidateVersion:match.expense.version,signature:match.signature,decision})});
      await load();openEditor(expense);notice(decision==="EXCLUDE"?"중복으로 제외했습니다. 기존 내역과 이번 증빙은 보존됩니다.":"선택한 기존 내역과 별개 거래로 확인했습니다. 남은 중복 후보와 입력 내용을 검토한 뒤 확정하세요.");
    }catch(error){showError(error.message);}finally{busy=false;BookkeepingUI.applyLocks();applyLocks();}
  }
  async function restore(){
    if(busy || !source)return;
    if(!confirm("중복 제외를 취소하고 다시 검토할까요? 중복 후보를 다시 확인합니다."))return;
    busy=true;applyLocks();
    try{
      const expense=await api(`/api/expenses/${source.id}/duplicate-restore`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({version:source.version})});
      await load();openEditor(expense);notice("중복 제외를 취소했습니다. 중복 후보를 확인한 뒤 확정해 주세요.");
    }catch(error){showError(error.message);}finally{busy=false;BookkeepingUI.applyLocks();applyLocks();}
  }
  function applyLocks(){
    const locked=busy || photoBusy || uploading || BookkeepingUI.isBusy();
    el("duplicate-panel").querySelectorAll("button").forEach(button=>button.disabled=locked);
    if(source?.duplicateState==="SUSPECTED")document.querySelectorAll('#edit-form button[type="submit"][value="CONFIRMED"]').forEach(b=>b.disabled=true);
    document.querySelectorAll(".close-editor").forEach(button=>button.disabled=locked);
  }
  async function open(expense){
    source=expense;const current=++revision;
    el("duplicate-error").hidden=true;el("duplicate-matches").replaceChildren();
    el("duplicate-panel").hidden=!expense.imagePath && expense.duplicateState!=="EXCLUDED";
    el("duplicate-title").textContent=expense.duplicateState==="EXCLUDED"?"중복으로 제외된 내역":expense.duplicateState==="SUSPECTED"?"⚠ 중복 의심 · 원본 비교가 필요합니다":"중복 검토";
    el("duplicate-help").textContent=expense.duplicateState==="EXCLUDED"?"집계·엑셀·기장 대상에서 제외됩니다. 증빙과 비교 근거는 보관하며 제외를 취소할 수 있습니다.":"확정 전 기존 증빙과 비교하세요. 같은 금액의 반복 구매는 별개 거래일 수 있습니다. 읽지 못한 승인번호는 필수값이 아닙니다.";
    el("duplicate-restore").hidden=expense.duplicateState!=="EXCLUDED";
    applyLocks();
    if(!expense.imagePath)return;
    try{
      const report=await api(`/api/expenses/${expense.id}/duplicates`);
      if(current!==revision || el("edit-id").value!==String(expense.id))return;
      if(!report.matches.length && expense.duplicateState!=="EXCLUDED"){el("duplicate-panel").hidden=true;return;}
      for(const match of report.matches){
        const card=node("article",null,"duplicate-match"),state=match.decision==="DISTINCT"?"별개 거래로 확인함":match.decision==="EXCLUDED"?"중복으로 제외함":match.expense.status==="CONFIRMED"?"이미 확정한 내역과 중복 의심":"기존 내역과 중복 의심";
        card.append(node("h3",state),node("p",match.reason,"duplicate-reason"));
        const pair=node("div",null,"duplicate-pair");pair.append(evidence(expense,"이번 등록"),evidence(match.expense,"비교할 기존 내역"));card.append(pair);
        if(match.decision==="PENDING" && expense.duplicateState!=="EXCLUDED" && expense.status==="REVIEW"){
          const actions=node("div",null,"duplicate-actions"),distinct=node("button","별개 거래로 확인","button"),exclude=node("button","이번 등록을 중복으로 제외","button duplicate-exclude");
          distinct.type=exclude.type="button";distinct.addEventListener("click",()=>decide(match,"DISTINCT"));exclude.addEventListener("click",()=>decide(match,"EXCLUDE"));actions.append(distinct,exclude);card.append(actions);
        }
        el("duplicate-matches").append(card);
      }
      applyLocks();
    }catch(error){if(current===revision)showError(error.message);}
  }
  function notifyUpload(results){
    const exact=results.filter(r=>r.duplicate),suspected=results.filter(r=>!r.duplicate && r.expense.duplicateState==="SUSPECTED");
    const confirmed=suspected.filter(r=>(r.duplicateMatches||[]).some(m=>m.expense.status==="CONFIRMED"));
    const exactConfirmed=exact.filter(r=>r.expense.status==="CONFIRMED");
    if(!exact.length && !suspected.length)return;
    const lines=[];
    if(exact.length)lines.push(`이미 등록된 동일 파일 ${exact.length}건은 추가 등록하지 않았습니다.${exactConfirmed.length?` 이 중 ${exactConfirmed.length}건은 이미 확정된 내역입니다.`:""}`);
    if(suspected.length)lines.push(`다른 형식·파일의 중복 의심 ${suspected.length}건은 빨간색 ‘중복 의심’으로 보관했습니다. 확인 전에는 확정할 수 없습니다.`);
    if(confirmed.length)lines.push(`이 중 ${confirmed.length}건은 이미 확정한 영수증과 중복일 수 있습니다. 기존 확정 내역은 변경하지 않았습니다.`);
    alert(lines.join("\n\n"));
  }
  document.addEventListener("DOMContentLoaded",()=>{
    el("duplicate-restore").addEventListener("click",restore);
    el("duplicate-filter").addEventListener("click",()=>{el("filter-status").value="SUSPECTED";load().catch(e=>notice(e.message,true));});
  });
  return {open,applyLocks,notifyUpload,isBusy:()=>busy};
})();
