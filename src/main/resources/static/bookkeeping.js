"use strict";

// 저장·확정한 영수증과 기장 화면을 연결합니다. 기장 입력은 영수증 저장 입력과 분리합니다.
const BookkeepingUI = (() => {
  const el = id => document.getElementById(id);
  let entries = [], source = null, busy = false;
  const activeFor = id => entries.find(e => e.expenseId === id && e.status === "POSTED");
  const hasHistory = id => entries.some(e => e.expenseId === id);
  function td(text, numeric=false) {
    const cell=document.createElement("td");cell.textContent=text;
    if(numeric)cell.className="numeric";return cell;
  }
  function statusCell(expense) {
    const cell=td(""), badge=document.createElement("span"), posted=activeFor(expense.id);
    badge.className="badge"+(posted?"":" review");
    badge.textContent=posted?"기장 완료":hasHistory(expense.id)?"취소 후 미기장":"미기장";cell.append(badge);
    if(posted){const note=document.createElement("small");note.textContent=posted.entryNumber;cell.append(note);}return cell;
  }
  function setEntries(value){entries=value;}

  // 집계는 기장일·검색 조건의 활성 전표만 사용합니다. 상태 선택은 화면 목록에만 적용합니다.
  function renderJournal() {
    const month=el("book-month").value, query=el("book-query").value.trim().toLowerCase();
    const matching=entries.filter(e=>(!month || e.entryDate.startsWith(month))
      && `${e.entryNumber} ${e.vendor} ${e.memo} ${e.debitAccount} ${e.creditAccount} ${e.approvalNumber || ""}`.toLowerCase().includes(query));
    const active=matching.filter(e=>e.status==="POSTED");
    el("book-posted-count").textContent=active.length+"건";
    el("book-posted-amount").textContent=money(active.reduce((sum,e)=>sum+e.totalAmount,0))+"원";
    el("book-cancelled-count").textContent=matching.filter(e=>e.status==="CANCELLED").length+"건";
    const visible=matching.filter(e=>!el("book-status").value || e.status===el("book-status").value);
    const body=el("book-body");body.replaceChildren();
    for(const entry of visible){
      const row=document.createElement("tr"), number=td(entry.entryDate), small=document.createElement("small");
      small.textContent=entry.entryNumber;number.append(small);
      const vendor=td(entry.vendor), memo=document.createElement("small");memo.textContent=entry.memo;vendor.append(memo);vendor.title=entry.memo;
      if(entry.approvalNumber){const approval=document.createElement("small");approval.textContent="승인번호 "+entry.approvalNumber;vendor.append(approval);}
      const state=td(entry.status==="POSTED"?"기장 완료":"기장 취소");
      if(entry.status==="CANCELLED"){
        row.classList.add("book-cancelled-row");const reason=document.createElement("small");
        reason.textContent=`${entry.cancelledBy} · ${entry.cancelReason}`;state.title=reason.textContent;state.append(reason);
      }
      const action=td(""), button=document.createElement("button");button.type="button";button.className="text-button";button.textContent="영수증 보기";
      button.addEventListener("click",async()=>{try{openEditor(await api(`/api/expenses/${entry.expenseId}`));}catch(e){notice(e.message,true);}});
      action.append(button);
      if(entry.imagePath){const evidence=document.createElement("a");evidence.href=`/api/bookkeeping/entries/${entry.id}/image`;evidence.target="_blank";evidence.rel="noopener";evidence.className="text-button";evidence.textContent="기장 당시 증빙";action.append(document.createElement("br"),evidence);}
      row.append(number,vendor,td(entry.debitAccount+(entry.vatAmount>0?" + 부가세대급금":"")),td(entry.creditAccount),
        td(money(entry.totalAmount)+" 원",true),td(entry.postedBy),state,action);body.append(row);
    }
    el("book-empty").hidden=visible.length>0;
    el("book-empty").textContent=entries.length?"조회 조건에 맞는 기장 전표가 없습니다.":"기장 전표가 없습니다. 영수증을 확정한 뒤 검토 창에서 전표를 기장하세요.";
    el("book-export").href="/api/bookkeeping/export.xlsx?"+new URLSearchParams({month,query:el("book-query").value.trim()});
  }

  function open(expense){
    source=expense;const posted=activeFor(expense.id);el("book-error").hidden=true;
    el("book-date").value=posted?.entryDate || expense.receiptDate || "";
    el("book-by").value=posted?.postedBy || expense.ownerName || "";
    el("book-credit").value=posted?.creditAccount || ({"법인카드":"미지급금(법인카드)","개인카드":"미지급금(임직원)","현금":"현금","계좌이체":"보통예금"}[expense.paymentMethod] || "");
    el("book-tax").value=posted?.taxMode || "TOTAL_EXPENSE";el("book-memo").value=posted?.memo || expense.description || "";
    el("book-cancel-by").value=expense.ownerName || posted?.postedBy || "";el("book-cancel-reason").value="";
    applyLocks();preview();
  }

  // 화면에서 기장 증빙을 잠그며 서버도 같은 조건을 검사합니다. 취소 이력이 있으면 삭제할 수 없습니다.
  function applyLocks(){
    if(!source)return;
    const posted=activeFor(source.id), excluded=source.duplicateState==="EXCLUDED",eligible=source.status==="CONFIRMED" && !excluded && source.duplicateState!=="SUSPECTED";
    for(const field of fields)el("edit-"+field).disabled=!!posted || excluded || busy;
    for(const id of ["rotate-left","rotate-right","reanalyze-button"])el(id).disabled=!!posted || excluded || busy;
    document.querySelectorAll('#edit-form button[type="submit"]').forEach(b=>b.disabled=!!posted || excluded || busy);
    el("delete-button").disabled=hasHistory(source.id) || busy;
    for(const id of ["book-date","book-by","book-credit","book-tax","book-memo"])el(id).disabled=!!posted || !eligible || busy;
    el("book-post").hidden=!!posted;el("book-post").disabled=!eligible || busy;
    el("book-cancel-panel").hidden=!posted;el("book-cancel").disabled=busy;
    el("book-entry-badge").textContent=posted?"기장 완료":hasHistory(source.id)?"취소 후 미기장":"미기장";
    el("book-entry-badge").className="badge"+(posted?"":" review");
    el("book-entry-info").textContent=posted
      ? `${posted.entryNumber} · ${posted.postedBy} · ${new Date(posted.postedAt).toLocaleString("ko-KR")} · 수정하려면 기장을 취소하세요.`
      : eligible?"영수증 확정과 기장은 별개입니다. 저장된 내역과 상대계정을 확인한 뒤 기장하세요."
      : "먼저 ‘확정하여 장부에 반영’을 눌러 저장하고, 영수증을 다시 열어 기장하세요.";
    if(excluded || source.duplicateState==="SUSPECTED")el("book-entry-info").textContent="중복 검토를 먼저 마쳐야 확정·기장할 수 있습니다.";
    DuplicateUI.applyLocks();
  }

  function preview(){
    if(!source)return;
    const posted=activeFor(source.id), rows=el("book-preview");rows.replaceChildren();
    const total=posted?.totalAmount ?? source.amount, split=(posted?.taxMode || el("book-tax").value)==="SPLIT_VAT";
    const vat=posted?.vatAmount ?? (split?source.vatAmount:0);
    if(!(total>0 && (!split || (vat>0 && source.supplyAmount>0 && source.supplyAmount+vat===total)))){
      el("book-preview-note").textContent="분개하려면 총금액과 선택한 세액을 원본에서 확인해 주세요.";return;
    }
    const lines=[["차변",posted?.debitAccount || source.category,total-vat]];
    if(vat>0)lines.push(["차변","부가세대급금",vat]);
    lines.push(["대변",posted?.creditAccount || el("book-credit").value || "상대계정 선택 필요",total]);
    for(const [side,account,amount]of lines){const row=document.createElement("tr");row.append(td(side),td(account),td(money(amount),true));rows.append(row);}
    el("book-preview-note").textContent=`차변 합계 ${money(total)}원 = 대변 합계 ${money(total)}원 · ${posted?"기장된 전표":"저장된 영수증 기준"}`;
  }

  function hasUnsavedChanges(){
    return fields.some(field=>{
      const value=el("edit-"+field).value.trim();
      return ["amount","supplyAmount","vatAmount"].includes(field)
        ? (value===""?null:Number(value))!==(source[field]??null):value!==String(source[field]??"").trim();
    });
  }

  async function perform(action){
    if(busy || !source)return;el("book-error").hidden=true;
    if(action==="post" && hasUnsavedChanges()){
      el("book-error").textContent="수정한 영수증 내용을 먼저 저장·확정한 뒤 기장해 주세요.";el("book-error").hidden=false;return;
    }
    const posted=activeFor(source.id);if(action==="cancel" && !posted)return;
    const body=action==="post"
      ? {version:source.version,entryDate:el("book-date").value,creditAccount:el("book-credit").value,taxMode:el("book-tax").value,postedBy:el("book-by").value.trim(),memo:el("book-memo").value.trim()}
      : {version:source.version,cancelledBy:el("book-cancel-by").value.trim(),reason:el("book-cancel-reason").value.trim()};
    if(action==="post" && (!body.entryDate || !body.creditAccount || !body.postedBy || !body.memo)){
      el("book-error").textContent="기장일·대변 상대계정·기장 담당자·전표 적요를 모두 입력해 주세요.";el("book-error").hidden=false;return;
    }
    if(action==="cancel" && (!body.reason || !body.cancelledBy)){
      el("book-error").textContent="취소 담당자와 사유를 입력해 주세요.";el("book-error").hidden=false;return;
    }
    busy=true;const controls=Array.from(el("editor").querySelectorAll("button,input,select,textarea")), previous=controls.map(c=>c.disabled);
    controls.forEach(c=>c.disabled=true);
    try{
      const result=await api(action==="post"?`/api/bookkeeping/expenses/${source.id}`:`/api/bookkeeping/entries/${posted.id}/cancel`,
        {method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify(body)});
      await load();openEditor(result.expense);
    }catch(error){el("book-error").textContent=error.message;el("book-error").hidden=false;}
    finally{busy=false;controls.forEach((c,i)=>c.disabled=previous[i]);applyLocks();}
  }

  async function init(){
    const settings=await api("/api/bookkeeping/config");options(el("book-credit"),settings.creditAccounts);
    el("book-post").addEventListener("click",()=>perform("post"));el("book-cancel").addEventListener("click",()=>perform("cancel"));
    for(const id of ["book-credit","book-tax"])el(id).addEventListener("change",preview);
    el("book-filters").addEventListener("submit",event=>{event.preventDefault();renderJournal();});
    for(const id of ["book-month","book-status"])el(id).addEventListener("change",renderJournal);
    el("book-reset").addEventListener("click",()=>{el("book-filters").reset();renderJournal();});
  }
  return {init,setEntries,renderJournal,statusCell,open,applyLocks,isBusy:()=>busy};
})();
