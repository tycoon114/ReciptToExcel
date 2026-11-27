"use strict";
window.ImportHome=(()=>{
  const labels={CARD_PDF:"법인카드 PDF",ACCOUNT_PDF:"계좌 입출금 PDF",TAX_INVOICE:"세금계산서",RECEIPT_PHOTO:"영수증 사진"};
  const el=id=>document.getElementById(id);
  let entries=[],busy=false;
  let analyses=new Map();
  const states={PENDING:"분석 대기",READY:"추출 완료",REVIEW:"원본 확인 필요",FAILED:"분석 실패",PHOTO_PENDING:"사진 처리 대기"};
  function message(text,error=false){el("import-status").textContent=text;el("import-status").hidden=false;el("import-status").classList.toggle("error",error);}
  function showView(view){
    const sections={imports:"import-home",monthly:"monthly-results",legacy:"legacy-workspace"};
    const buttons={imports:["imports-nav","mobile-imports"],monthly:["monthly-nav","mobile-monthly"],legacy:["legacy-nav","mobile-legacy"]};
    for(const [name,id] of Object.entries(sections)){
      const active=name===view;
      el(id).hidden=!active;
      for(const buttonId of buttons[name]){
        const button=el(buttonId);
        button.classList.toggle("nav-active",active);
        if(active)button.setAttribute("aria-current","page");
        else button.removeAttribute("aria-current");
      }
    }
    document.title={imports:"자료 가져오기",monthly:"월별 결과",legacy:"경비 지출장부"}[view]+" | ReceiptToExcel";
    window.scrollTo(0,0);
    window.dispatchEvent(new CustomEvent("workspace-view",{detail:view}));
  }
  function render(){
    const body=el("import-rows");body.replaceChildren();
    const selected=el("import-kind-filter").value;
    const visible=entries.filter(entry=>!selected || entry.kind===selected);
    for(const entry of visible){
      const row=document.createElement("tr");
      for(const text of [entry.name,labels[entry.kind],entry.pages?entry.pages+"쪽":Math.ceil(entry.size/1024)+" KB",
        new Date(entry.uploadedAt).toLocaleString("ko-KR"),status(entry)]){
        const cell=document.createElement("td");cell.textContent=text;row.append(cell);
      }
      const cell=document.createElement("td"),link=document.createElement("a");
      link.textContent="원본 받기";link.className="text-button";link.href="/api/imports/"+encodeURIComponent(entry.id)+"/original";
      cell.append(link);row.append(cell);body.append(row);
    }
    el("import-empty").hidden=visible.length>0;
    el("import-empty").textContent=selected?"이 종류로 등록한 자료가 없습니다.":"위에서 자료를 선택해 등록해 주세요.";
    el("import-count").textContent=visible.length+"개";
    el("import-analyze").disabled=busy || !entries.some(entry=>entry.mediaType==="application/pdf");
  }
  function status(entry){
    const result=analyses.get(entry.id);
    if(!result)return entry.mediaType==="application/pdf"?"분석 대기":"사진 처리 대기";
    return states[result.status]+(result.analyzedAt?" · "+result.rows.length+"건":"");
  }
  async function refresh(){
    const [files,documents]=await Promise.all([api("/api/imports"),api("/api/imports/analysis")]);
    entries=files;analyses=new Map(documents.map(document=>[document.file.id,document.analysis]));render();
    window.dispatchEvent(new Event("imports-changed"));
  }
  function lock(value){
    busy=value;
    document.querySelectorAll("[data-import-kind]").forEach(input=>input.disabled=value);
    el("import-refresh").disabled=value;
    render();
  }
  async function analyze(){
    if(busy)return;
    const documents=entries.filter(entry=>entry.mediaType==="application/pdf");
    lock(true);let ready=0;const errors=[];
    el("import-analyze").textContent="분석 중…";
    try{
      for(let i=0;i<documents.length;i++){
        const file=documents[i];message(`${i+1}/${documents.length} · ${file.name} 분석 중…`);
        try{
          const result=await api("/api/imports/"+encodeURIComponent(file.id)+"/analyze",{method:"POST"});
          analyses.set(file.id,result);render();
          if(result.status==="READY")ready++;
          else errors.push(file.name+" · "+result.issues.map(issue=>(issue.page?issue.page+"쪽 · ":"")+issue.message).join(" / "));
        }catch(error){errors.push(file.name+" · "+error.message);}
      }
      message(`분석 완료 ${ready}/${documents.length}개. 월별 결과에서 엑셀을 만들 수 있습니다.`+(errors.length?"\n"+errors.join("\n"):""),errors.length>0);
      await refresh();
    }finally{lock(false);el("import-analyze").textContent="등록 자료 분석";}
  }
  async function upload(input){
    if(busy || !input.files.length)return;
    lock(true);
    const files=Array.from(input.files),kind=input.dataset.importKind;
    const inputs=Array.from(document.querySelectorAll("[data-import-kind]"));
    inputs.forEach(node=>node.disabled=true);
    let saved=0;const errors=[];
    try{
      for(let i=0;i<files.length;i++){
        const file=files[i],limit=kind==="RECEIPT_PHOTO"?10:25;
        message(`${i+1}/${files.length} · ${file.name} 등록 중…`);
        if(file.size>limit*1024*1024){errors.push(file.name+` · ${limit}MB를 초과했습니다.`);continue;}
        const data=new FormData();data.append("kind",kind);data.append("file",file);
        try{const entry=await api("/api/imports",{method:"POST",body:data});entries.unshift(entry);saved++;render();}
        catch(error){errors.push(file.name+" · "+error.message);}
      }
      message(`${labels[kind]} ${saved}개를 등록했습니다.`+(errors.length?"\n"+errors.join("\n"):""),errors.length>0);
    }finally{lock(false);input.value="";}
  }
  function init(){
    el("monthly-nav").addEventListener("click",()=>showView("monthly"));
    el("mobile-monthly").addEventListener("click",()=>showView("monthly"));
    el("mobile-imports").addEventListener("click",()=>showView("imports"));
    el("imports-nav").addEventListener("click",()=>showView("imports"));
    el("legacy-nav").addEventListener("click",()=>showView("legacy"));
    el("mobile-legacy").addEventListener("click",()=>showView("legacy"));
    el("legacy-back").addEventListener("click",()=>showView("imports"));
    el("import-refresh").addEventListener("click",()=>refresh().catch(error=>message(error.message,true)));
    el("import-analyze").addEventListener("click",()=>analyze().catch(error=>message(error.message,true)));
    el("import-kind-filter").addEventListener("change",render);
    document.querySelectorAll("[data-import-kind]").forEach(input=>input.addEventListener("change",()=>upload(input).catch(error=>message(error.message,true))));
    showView("imports");
    refresh().catch(error=>message("자료 목록을 불러오지 못했습니다. "+error.message,true));
  }
  return {init,showView};
})();
ImportHome.init();
