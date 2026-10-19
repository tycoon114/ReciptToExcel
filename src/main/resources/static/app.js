"use strict";
const $ = id => document.getElementById(id);
let config, rows = [], uploading = false, loadRevision = 0, photoBusy = false;
const fields = ["receiptDate","vendor","category","description","amount","supplyAmount","vatAmount","paymentMethod","ownerName","department","note"];
const money = n => n == null ? "미확인" : new Intl.NumberFormat("ko-KR").format(n);
async function api(path, options = {}) {
  const response = await fetch(path, {...options, headers: {"X-Receipt-Client":"local-app", ...options.headers}});
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data.message || `요청 실패 (${response.status}). 입력과 연결 상태를 확인해 주세요.`);
  return data;
}
function notice(message, error = false) { $("notice").textContent = message; $("notice").classList.toggle("error", error); $("notice").hidden = false; }
function options(select, values) { for (const value of values) { const option = document.createElement("option"); option.value = value; option.textContent = value; select.append(option); } }
function filters() { return new URLSearchParams({month:$("filter-month").value,category:$("filter-category").value,status:$("filter-status").value,query:$("filter-query").value.trim(),sort:$("filter-sort").value}); }
async function load() {
  const revision = ++loadRevision;
  const result = await api("/api/expenses?"+filters());
  if (revision !== loadRevision) return;
  rows = result; render();
  const params = filters(); params.delete("status"); $("export-link").href = "/api/export.xlsx?"+params;
}
function cell(value, className) { const td = document.createElement("td"); td.textContent = value; if(className) td.className = className; return td; }
function render() {
  const body = $("ledger-body"); body.replaceChildren();
  let amount = 0, confirmed = 0, review = 0;
  for(const expense of rows) {
    if(expense.status === "CONFIRMED") { amount += expense.amount || 0; confirmed++; } else review++;
    const tr = document.createElement("tr");
    tr.append(cell(expense.receiptDate || "날짜 미확인"));
    const vendor = cell(expense.vendor || "거래처 미확인"); const description = document.createElement("small"); description.textContent = expense.description || "적요를 입력해 주세요"; vendor.append(description); tr.append(vendor);
    const category = cell(""); const tag = document.createElement("span"); tag.className="category-tag"; tag.textContent=expense.category; category.append(tag); tr.append(category);
    const note = cell(expense.note || "—"); note.title=expense.note; tr.append(note,cell(expense.paymentMethod),cell(money(expense.amount)+(expense.amount==null?"":" 원"),"numeric"));
    const status = cell(""); const badge = document.createElement("span"); badge.className="badge"+(expense.status==="CONFIRMED"?"":" review"); badge.textContent=expense.status==="CONFIRMED"?"확정":"검토 대기"; status.append(badge); tr.append(status);
    const action=cell(""); const button=document.createElement("button"); button.className="text-button"; button.textContent="검토 →"; button.setAttribute("aria-label",(expense.vendor||"영수증")+" 검토"); action.append(button); tr.append(action);
    tr.addEventListener("click",()=>openEditor(expense)); body.append(tr);
  }
  $("total-amount").replaceChildren(document.createTextNode(money(amount))); const unit=document.createElement("span"); unit.textContent=" 원"; $("total-amount").append(unit);
  $("confirmed-count").textContent=confirmed+" 건"; $("review-count").textContent=review+" 건"; $("row-count").textContent=rows.length;
  $("empty-state").hidden=rows.length>0;
  $("empty-state").querySelector("h3").textContent=filters().get("query") || filters().get("month") || filters().get("category") || filters().get("status")?"조회 조건에 맞는 내역이 없습니다":"첫 영수증을 등록해 보세요";
}
function openEditor(expense) {
  $("edit-id").value=expense.id; $("edit-version").value=expense.version;
  for(const field of fields) $("edit-"+field).value=expense[field]??"";
  $("receipt-preview").hidden=!expense.imagePath; $("original-link").hidden=!expense.imagePath; $("no-image").hidden=!!expense.imagePath;
  $("photo-tools").hidden=!expense.imagePath;
  if(expense.imagePath) { $("receipt-preview").src=`/api/expenses/${expense.id}/image?v=${expense.version}`; $("original-link").href=`/api/expenses/${expense.id}/image?v=${expense.version}`; } else $("receipt-preview").removeAttribute("src");
  $("analysis-source").textContent=expense.analysisSource==="OLLAMA"?"로컬 AI 분석":expense.analysisSource==="OCR_RULES"?"기본 모드 · Windows OCR + 규칙 분류":"직접 입력";
  $("analysis-message").textContent=expense.analysisMessage; $("ocr-text").textContent=expense.ocrText||"인식된 원문이 없습니다."; $("edit-error").hidden=true;
  if(!$("editor").open) $("editor").showModal();
}
async function upload(files) {
  if(uploading || !files.length) return;
  uploading=true; $("receipt-files").disabled=true;
  const selected=Array.from(files); let saved=0, duplicate=0; const errors=[]; let latest;
  try {
    for(let i=0;i<selected.length;i++) {
      const file=selected[i]; $("upload-label").textContent=`${i+1} / ${selected.length} · 인식 중…`;
      notice(`사진을 인식하고 있습니다 (${i+1}/${selected.length}). `+(config.aiEnabled?"로컬 AI는 사진당 최대 약 3분이 걸릴 수 있습니다.":"잠시 기다려 주세요."));
      if(file.size>10*1024*1024) { errors.push(file.name+": 10MB 초과"); continue; }
      const data=new FormData(); data.append("file",file); data.append("hint",$("upload-hint").value.trim());
      try { const result=await api("/api/receipts",{method:"POST",body:data}); latest=result.expense; result.duplicate?duplicate++:saved++; }
      catch(error) { errors.push(file.name+": "+error.message); }
    }
    await load();
    notice(`신규 등록 ${saved}건 · 중복 ${duplicate}건`+(errors.length?"\n"+errors.join("\n"):". 원본과 인식 내용을 확인한 뒤 확정해 주세요."),!!errors.length);
    if(selected.length===1 && latest) openEditor(latest);
  } catch(error) { notice(error.message,true); }
  finally { uploading=false; $("receipt-files").disabled=false; $("receipt-files").value=""; $("upload-label").textContent="사진을 끌어 놓거나 선택하세요"; }
}
$("receipt-files").addEventListener("change",e=>upload(e.target.files));
const drop=$("dropzone");
drop.addEventListener("dragover",e=>{e.preventDefault();drop.classList.add("dragging");}); drop.addEventListener("dragleave",()=>drop.classList.remove("dragging")); drop.addEventListener("drop",e=>{e.preventDefault();drop.classList.remove("dragging");upload(e.dataTransfer.files);});
window.addEventListener("dragover",e=>e.preventDefault()); window.addEventListener("drop",e=>e.preventDefault());
$("filters").addEventListener("submit",e=>{e.preventDefault();load().catch(e=>notice(e.message,true));});
$("reset-button").addEventListener("click",()=>{$("filters").reset();load().catch(e=>notice(e.message,true));});
for(const id of ["filter-month","filter-category","filter-status","filter-sort"]) $(id).addEventListener("change",()=>load().catch(e=>notice(e.message,true)));
$("refresh-button").addEventListener("click",()=>load().catch(e=>notice(e.message,true)));
$("manual-button").addEventListener("click",async()=>{try { const expense=await api("/api/expenses",{method:"POST"}); await load(); openEditor(expense); } catch(e) { notice(e.message,true); }});
document.querySelectorAll(".close-editor").forEach(b=>b.addEventListener("click",()=>$("editor").close()));
$("editor").addEventListener("cancel",e=>{if(photoBusy)e.preventDefault();});
async function photoAction(action, quarterTurns=0) {
  if(photoBusy)return;
  if(action==="reanalyze" && !confirm("날짜·거래처·계정과목·금액·결제수단을 다시 인식하고 검토 대기로 변경합니다. 적요·비고·사용자·부서는 유지합니다. 진행할까요?"))return;
  const preserved=action==="rotate"?fields:["description","note","ownerName","department"];
  const draft=Object.fromEntries(preserved.filter(field=>action==="rotate" || $("edit-"+field).value.trim()!=="").map(field=>[field,$("edit-"+field).value]));
  const id=$("edit-id").value, version=Number($("edit-version").value);
  const hint=$("edit-description").value.trim();
  photoBusy=true;
  const controls=Array.from($("editor").querySelectorAll("button,input,select,textarea"));controls.forEach(control=>control.disabled=true);
  $("analysis-message").textContent=action==="rotate"?"사진 방향을 저장하고 있습니다…":"사진을 다시 읽고 있습니다. 잠시 기다려 주세요…";
  try {
    const expense=await api(`/api/expenses/${id}/${action}`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({version,quarterTurns,hint})});
    await load();openEditor(expense);
    for(const [field,value] of Object.entries(draft))$("edit-"+field).value=value;
    if(action==="rotate")$("analysis-message").textContent="사진 방향을 저장했습니다. ‘사진 다시 읽기’로 인식을 다시 실행할 수 있습니다.";
  } catch(error) {$("edit-error").textContent=error.message;$("edit-error").hidden=false;}
  finally {photoBusy=false;controls.forEach(control=>control.disabled=false);}
}
$("rotate-left").addEventListener("click",()=>photoAction("rotate",-1));
$("rotate-right").addEventListener("click",()=>photoAction("rotate",1));
$("reanalyze-button").addEventListener("click",()=>photoAction("reanalyze"));
$("edit-form").addEventListener("submit",async e=>{
  e.preventDefault(); const data={version:Number($("edit-version").value),status:e.submitter?.value||"REVIEW"};
  for(const field of fields) { const value=$("edit-"+field).value.trim(); data[field]=["amount","supplyAmount","vatAmount"].includes(field)?(value===""?null:Number(value)):value; }
  const buttons=Array.from($("edit-form").querySelectorAll("button")); buttons.forEach(b=>b.disabled=true);
  try { await api("/api/expenses/"+$("edit-id").value,{method:"PUT",headers:{"Content-Type":"application/json"},body:JSON.stringify(data)}); $("editor").close(); await load(); notice(data.status==="CONFIRMED"?"영수증을 확정했습니다. 장부와 엑셀 내보내기에 반영됩니다.":"검토 대기로 저장했습니다."); }
  catch(error) { $("edit-error").textContent=error.message; $("edit-error").hidden=false; }
  finally { buttons.forEach(b=>b.disabled=false); }
});
$("delete-button").addEventListener("click",async()=>{if(!confirm("이 영수증 내역과 사진을 삭제할까요?"))return;try { await api("/api/expenses/"+$("edit-id").value,{method:"DELETE"}); $("editor").close(); await load(); notice("영수증을 삭제했습니다."); } catch(e) { $("edit-error").textContent=e.message; $("edit-error").hidden=false; }});
$("guide-button").addEventListener("click",()=>$("guide").showModal()); $("close-guide").addEventListener("click",()=>$("guide").close());
$("sync-button").addEventListener("click",async()=>{
  if(!config.sheetsConfigured) { $("guide").showModal(); return; }
  if(!confirm("확정된 전체 장부를 Google Sheets로 전송합니다. ‘영수증장부_APP’ 탭이 현재 PC 장부로 갱신됩니다. 진행할까요?")) return;
  $("sync-button").disabled=true;
  try { const result=await api("/api/sheets/sync",{method:"POST"}); notice(result.message); }
  catch(e) { notice(e.message,true); } finally { $("sync-button").disabled=false; }
});
async function init() {
  try { config=await api("/api/config"); options($("filter-category"),config.categories); options($("edit-category"),config.categories); options($("edit-paymentMethod"),config.payments);
    $("mode-label").textContent=config.aiEnabled?"로컬 AI 연결 모드 · "+config.aiModel:"무료 기본 모드 · OCR + 규칙";
    $("sheets-state").textContent=config.sheetsConfigured?"직접 연결 설정됨":"파일 가져오기"; $("sync-button").textContent=config.sheetsConfigured?"전체 확정 장부 전송 ↗":"연결 및 전송 안내 ↗";
    await load();
  } catch(e) { notice("앱을 불러오지 못했습니다. "+e.message,true); }
}
init();
