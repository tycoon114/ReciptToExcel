"use strict";
window.ImportHome=(()=>{
  const labels={CARD_PDF:"법인카드 PDF",ACCOUNT_PDF:"계좌 입출금 PDF",TAX_INVOICE:"세금계산서",RECEIPT_PHOTO:"영수증 사진"};
  const el=id=>document.getElementById(id);
  let entries=[],busy=false;
  function message(text,error=false){el("import-status").textContent=text;el("import-status").hidden=false;el("import-status").classList.toggle("error",error);}
  function showLegacy(value){
    el("import-home").hidden=value;el("legacy-workspace").hidden=!value;
    el("imports-nav").classList.toggle("nav-active",!value);
    el("legacy-nav").classList.toggle("nav-active",value);
    window.scrollTo(0,0);
  }
  function render(){
    const body=el("import-rows");body.replaceChildren();
    const selected=el("import-kind-filter").value;
    const visible=entries.filter(entry=>!selected || entry.kind===selected);
    for(const entry of visible){
      const row=document.createElement("tr");
      for(const text of [entry.name,labels[entry.kind],entry.pages?entry.pages+"쪽":Math.ceil(entry.size/1024)+" KB",
        new Date(entry.uploadedAt).toLocaleString("ko-KR"),entry.kind==="RECEIPT_PHOTO"?"사진 연결 대기":"거래 추출 대기"]){
        const cell=document.createElement("td");cell.textContent=text;row.append(cell);
      }
      const cell=document.createElement("td"),link=document.createElement("a");
      link.textContent="원본 받기";link.className="text-button";link.href="/api/imports/"+encodeURIComponent(entry.id)+"/original";
      cell.append(link);row.append(cell);body.append(row);
    }
    el("import-empty").hidden=visible.length>0;
    el("import-empty").textContent=selected?"이 종류로 등록한 자료가 없습니다.":"위에서 자료를 선택해 등록해 주세요.";
    el("import-count").textContent=visible.length+"개";
  }
  async function refresh(){entries=await api("/api/imports");render();}
  async function upload(input){
    if(busy || !input.files.length)return;
    busy=true;
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
    }finally{busy=false;inputs.forEach(node=>node.disabled=false);input.value="";}
  }
  function init(){
    el("imports-nav").addEventListener("click",()=>showLegacy(false));
    el("legacy-nav").addEventListener("click",()=>showLegacy(true));
    el("mobile-legacy").addEventListener("click",()=>showLegacy(true));
    el("legacy-back").addEventListener("click",()=>showLegacy(false));
    el("import-refresh").addEventListener("click",()=>refresh().catch(error=>message(error.message,true)));
    el("import-kind-filter").addEventListener("change",render);
    document.querySelectorAll("[data-import-kind]").forEach(input=>input.addEventListener("change",()=>upload(input).catch(error=>message(error.message,true))));
    refresh().catch(error=>message("자료 목록을 불러오지 못했습니다. "+error.message,true));
  }
  return {init};
})();
ImportHome.init();
