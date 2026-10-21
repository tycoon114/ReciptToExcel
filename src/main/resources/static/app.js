// 화면 처리 진입점: 설정 조회 → 장부 목록 표시 → 사진 업로드·검토·저장 순서로 서버 API와 연결합니다.
"use strict";
const $ = id => document.getElementById(id);
// rows는 현재 조회 결과입니다. 목록 요청과 사진 작업의 진행 상태를 분리해 중복 동작을 줄입니다.
let config, rows = [], uploading = false, loadRevision = 0, photoBusy = false;
// 검토 폼의 입력 ID는 edit-필드명 규칙을 따르며, 서버 ExpenseInput과 같은 이름을 사용합니다.
const fields = ["receiptDate","vendor","category","description","amount","supplyAmount","vatAmount","paymentMethod","ownerName","department","note"];
const money = n => n == null ? "미확인" : new Intl.NumberFormat("ko-KR").format(n);
// 변경 요청 표시 헤더와 JSON 오류 처리를 한곳에 모읍니다. 이 헤더는 로그인 인증 수단은 아닙니다.
async function api(path, options = {}) {
  const response = await fetch(path, {...options, headers: {"X-Receipt-Client":"local-app", ...options.headers}});
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data.message || `요청 실패 (${response.status}). 입력과 연결 상태를 확인해 주세요.`);
  return data;
}
// 성공 안내와 오류 안내는 같은 알림 영역을 사용하며 스타일과 접근성 상태를 함께 갱신합니다.
function notice(message, error = false) { $("notice").textContent = message; $("notice").classList.toggle("error", error); $("notice").hidden = false; }
// 서버가 제공한 분류·결제수단 목록을 선택지로 넣어 화면과 저장 검증의 값이 어긋나지 않게 합니다.
function options(select, values) { for (const value of values) { const option = document.createElement("option"); option.value = value; option.textContent = value; select.append(option); } }
// 조회와 엑셀 링크가 같은 월·분류·검색·정렬 값을 사용하도록 검색 조건을 직렬화합니다.
function filters() { return new URLSearchParams({month:$("filter-month").value,category:$("filter-category").value,status:$("filter-status").value,query:$("filter-query").value.trim(),sort:$("filter-sort").value}); }
// 빠르게 필터를 바꿔도 늦게 도착한 과거 응답이 최신 목록을 덮어쓰지 않게 요청 번호를 비교합니다.
async function load() {
  const revision = ++loadRevision;
  const [result, bookEntries] = await Promise.all([api("/api/expenses?"+filters()),api("/api/bookkeeping/entries")]);
  if (revision !== loadRevision) return;
  BookkeepingUI.setEntries(bookEntries);
  rows = result; render();BookkeepingUI.renderJournal();
  // 엑셀은 상태 필터와 관계없이 확정 건만 출력하므로 조회 상태만 다운로드 조건에서 뺍니다.
  const params = filters(); params.delete("status"); $("export-link").href = "/api/export.xlsx?"+params;
}
// OCR와 입력 문구는 HTML로 해석하지 않고 textContent로 표시합니다.
function cell(value, className) { const td = document.createElement("td"); td.textContent = value; if(className) td.className = className; return td; }
// 현재 조회 결과를 그립니다. 합계에는 확정 건만 더하고 검토 건은 별도로 셉니다.
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
    const status = cell(""); const badge = document.createElement("span"); badge.className="badge"+(expense.status==="CONFIRMED"?"":" review"); badge.textContent=expense.status==="CONFIRMED"?"확정":"검토 대기"; status.append(badge); tr.append(status,BookkeepingUI.statusCell(expense));
    const action=cell(""); const button=document.createElement("button"); button.className="text-button"; button.textContent="검토 →"; button.setAttribute("aria-label",(expense.vendor||"영수증")+" 검토"); action.append(button); tr.append(action);
    tr.addEventListener("click",()=>openEditor(expense)); body.append(tr);
  }
  $("total-amount").replaceChildren(document.createTextNode(money(amount))); const unit=document.createElement("span"); unit.textContent=" 원"; $("total-amount").append(unit);
  $("confirmed-count").textContent=confirmed+" 건"; $("review-count").textContent=review+" 건"; $("row-count").textContent=rows.length;
  $("empty-state").hidden=rows.length>0;
  $("empty-state").querySelector("h3").textContent=filters().get("query") || filters().get("month") || filters().get("category") || filters().get("status")?"조회 조건에 맞는 내역이 없습니다":"첫 영수증을 등록해 보세요";
}
// 선택 내역을 검토 폼에 채웁니다. 사진 주소에 버전을 넣어 회전 후 최신 이미지가 보이게 합니다.
function openEditor(expense) {
  $("edit-id").value=expense.id; $("edit-version").value=expense.version;
  for(const field of fields) $("edit-"+field).value=expense[field]??"";
  $("receipt-preview").hidden=!expense.imagePath; $("original-link").hidden=!expense.imagePath; $("no-image").hidden=!!expense.imagePath;
  $("photo-tools").hidden=!expense.imagePath;
  if(expense.imagePath) { $("receipt-preview").src=`/api/expenses/${expense.id}/image?v=${expense.version}`; $("original-link").href=`/api/expenses/${expense.id}/image?v=${expense.version}`; } else $("receipt-preview").removeAttribute("src");
  $("analysis-source").textContent=expense.analysisSource==="OLLAMA"?"로컬 AI 분석":expense.analysisSource==="OCR_RULES"?"기본 모드 · Windows OCR + 규칙 분류":"직접 입력";
  $("analysis-message").textContent=expense.analysisMessage; $("ocr-text").textContent=expense.ocrText||"인식된 원문이 없습니다."; $("edit-error").hidden=true;
  BookkeepingUI.open(expense);
  if(!$("editor").open) $("editor").showModal();
}
// 여러 사진은 순서대로 업로드합니다. 개별 실패가 있어도 나머지 사진을 계속 처리하고 결과를 합쳐 안내합니다.
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
      try {
            const result=await api("/api/receipts",
                {method:"POST",body:data});

            if(result.duplicate){
              duplicate++;

              //중복된 사진 알리기
              if(selected.length===1){
                alert(`${file.name}\n이미 등록된 영수증입니다. 추가 등록하지 않았습니다.`);
              }
            }else{
                saved++;

                //신규 등록 영수증만 검토창 열기
                latest = result.expense;
            }
      }
      catch(error) { errors.push(file.name+": "+error.message); }
    }
    await load();
    notice(
        `신규 등록 ${saved}건 · 중복으로 등록 제외 ${duplicate}건` +
        (errors.length ? "\n" + errors.join("\n") : ""),
        errors.length > 0
    );
    if(selected.length===1 && latest) openEditor(latest);
  } catch(error) { notice(error.message,true); }
  finally { uploading=false; $("receipt-files").disabled=false; $("receipt-files").value=""; $("upload-label").textContent="사진을 끌어 놓거나 선택하세요"; }
}
// 파일 선택과 끌어 놓기는 같은 업로드 함수로 연결합니다. 브라우저의 파일 열기 기본 동작은 막습니다.
$("receipt-files").addEventListener("change",e=>upload(e.target.files));
const drop=$("dropzone");
drop.addEventListener("dragover",e=>{e.preventDefault();drop.classList.add("dragging");}); drop.addEventListener("dragleave",()=>drop.classList.remove("dragging")); drop.addEventListener("drop",e=>{e.preventDefault();drop.classList.remove("dragging");upload(e.dataTransfer.files);});
window.addEventListener("dragover",e=>e.preventDefault()); window.addEventListener("drop",e=>e.preventDefault());
// 조회·초기화·수동 입력 등 목록 화면의 사용자 동작을 연결합니다.
$("filters").addEventListener("submit",e=>{e.preventDefault();load().catch(e=>notice(e.message,true));});
$("reset-button").addEventListener("click",()=>{$("filters").reset();load().catch(e=>notice(e.message,true));});
for(const id of ["filter-month","filter-category","filter-status","filter-sort"]) $(id).addEventListener("change",()=>load().catch(e=>notice(e.message,true)));
$("refresh-button").addEventListener("click",()=>load().catch(e=>notice(e.message,true)));
$("manual-button").addEventListener("click",async()=>{try { const expense=await api("/api/expenses",{method:"POST"}); await load(); openEditor(expense); } catch(e) { notice(e.message,true); }});
document.querySelectorAll(".close-editor").forEach(b=>b.addEventListener("click",()=>{if(!photoBusy && !BookkeepingUI.isBusy())$("editor").close();}));
$("editor").addEventListener("cancel",e=>{if(photoBusy || BookkeepingUI.isBusy())e.preventDefault();});
// 회전은 입력 초안을 모두 보존하고, 재인식은 직접 작성한 문맥을 보존하면서 추출 필드만 갱신합니다.
async function photoAction(action, quarterTurns=0) {
  if(photoBusy)return;
  if(action==="reanalyze" && !confirm("날짜·거래처·계정과목·금액·결제수단을 다시 인식하고 검토 대기로 변경합니다. 직접 작성한 적요·비고·사용자·부서는 유지합니다. 이전 오분류의 ‘도서 구입’ 기본값은 재검토합니다. 진행할까요?"))return;
  // 저장 전의 입력도 보호합니다. 빈 문맥은 재인식 결과가 제안한 값을 받을 수 있게 제외합니다.
  const preserved=action==="rotate"?fields:["description","note","ownerName","department"];
  const draft=Object.fromEntries(preserved.filter(field=>action==="rotate" || $("edit-"+field).value.trim()!=="").map(field=>[field,$("edit-"+field).value]));
  const id=$("edit-id").value, version=Number($("edit-version").value);
  const hint=$("edit-description").value.trim();
  photoBusy=true;
  // 사진 작업 중 중복 저장·회전·닫기를 줄이고 성공·실패와 무관하게 마지막에 조작을 다시 허용합니다.
  const controls=Array.from($("editor").querySelectorAll("button,input,select,textarea"));controls.forEach(control=>control.disabled=true);
  $("analysis-message").textContent=action==="rotate"?"사진 방향을 저장하고 있습니다…":"사진을 다시 읽고 있습니다. 잠시 기다려 주세요…";
  try {
    const expense=await api(`/api/expenses/${id}/${action}`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({version,quarterTurns,hint})});
    // 서버가 이전 오분류의 기본 문구를 비웠다면, 화면 초안이 그 문구를 다시 덮어쓰지 않게 합니다.
    if(action==="reanalyze" && expense.category!=="도서인쇄비") {
      for(const field of ["description","note"]) if(draft[field]==="도서 구입" && expense[field]==="") delete draft[field];
    }
    await load();openEditor(expense);
    for(const [field,value] of Object.entries(draft))$("edit-"+field).value=value;
    if(action==="rotate")$("analysis-message").textContent="사진 방향을 저장했습니다. ‘사진 다시 읽기’로 인식을 다시 실행할 수 있습니다.";
  } catch(error) {$("edit-error").textContent=error.message;$("edit-error").hidden=false;}
  finally {photoBusy=false;controls.forEach(control=>control.disabled=false);BookkeepingUI.applyLocks();}
}
$("rotate-left").addEventListener("click",()=>photoAction("rotate",-1));
$("rotate-right").addEventListener("click",()=>photoAction("rotate",1));
$("reanalyze-button").addEventListener("click",()=>photoAction("reanalyze"));
// 금액 빈칸은 null, 입력한 0은 숫자 0으로 보내 미확인과 0원을 구분합니다. 저장 상태는 누른 버튼으로 결정합니다.
$("edit-form").addEventListener("submit",async e=>{
  e.preventDefault(); const data={version:Number($("edit-version").value),status:e.submitter?.value||"REVIEW"};
  for(const field of fields) { const value=$("edit-"+field).value.trim(); data[field]=["amount","supplyAmount","vatAmount"].includes(field)?(value===""?null:Number(value)):value; }
  const buttons=Array.from($("edit-form").querySelectorAll("button")); buttons.forEach(b=>b.disabled=true);
  try { await api("/api/expenses/"+$("edit-id").value,{method:"PUT",headers:{"Content-Type":"application/json"},body:JSON.stringify(data)}); $("editor").close(); await load(); notice(data.status==="CONFIRMED"?"영수증을 확정했습니다. 장부와 엑셀 내보내기에 반영됩니다.":"검토 대기로 저장했습니다."); }
  catch(error) { $("edit-error").textContent=error.message; $("edit-error").hidden=false; }
  finally { buttons.forEach(b=>b.disabled=false);BookkeepingUI.applyLocks(); }
});
// 삭제는 선택 내역과 증빙 사진을 함께 없애므로 사용자 확인을 받은 뒤 요청합니다.
$("delete-button").addEventListener("click",async()=>{if(!confirm("이 영수증 내역과 사진을 삭제할까요?"))return;try { await api("/api/expenses/"+$("edit-id").value,{method:"DELETE"}); $("editor").close(); await load(); notice("영수증을 삭제했습니다."); } catch(e) { $("edit-error").textContent=e.message; $("edit-error").hidden=false; }});
$("guide-button").addEventListener("click",()=>$("guide").showModal()); $("close-guide").addEventListener("click",()=>$("guide").close());
// 구글 전송은 현재 필터가 아닌 확정된 전체 장부입니다. 전용 탭 갱신 범위를 확인한 뒤 요청합니다.
$("sync-button").addEventListener("click",async()=>{
  if(!config.sheetsConfigured) { $("guide").showModal(); return; }
  if(!confirm("확정된 전체 장부를 Google Sheets로 전송합니다. ‘영수증장부_APP’ 탭이 현재 PC 장부로 갱신됩니다. 진행할까요?")) return;
  $("sync-button").disabled=true;
  try { const result=await api("/api/sheets/sync",{method:"POST"}); notice(result.message); }
  catch(e) { notice(e.message,true); } finally { $("sync-button").disabled=false; }
});
// 처음 열 때 설정·선택 목록·연결 안내를 준비하고 장부를 조회합니다.
async function init() {
  try { config=await api("/api/config"); options($("filter-category"),config.categories); options($("edit-category"),config.categories); options($("edit-paymentMethod"),config.payments);
    $("mode-label").textContent=config.aiEnabled?"로컬 AI 연결 모드 · "+config.aiModel:"무료 기본 모드 · OCR + 규칙";
    $("sheets-state").textContent=config.sheetsConfigured?"직접 연결 설정됨":"파일 가져오기"; $("sync-button").textContent=config.sheetsConfigured?"전체 확정 장부 전송 ↗":"연결 및 전송 안내 ↗";
    await BookkeepingUI.init();await load();
  } catch(e) { notice("앱을 불러오지 못했습니다. "+e.message,true); }
}
init();
