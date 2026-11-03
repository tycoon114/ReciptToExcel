"use strict";

// 등록 탭의 종류는 업로드 시작 시 고정합니다. 장부 조회·중복 검토의 범위는 별도로 유지합니다.
window.DocumentForms=(()=>{
  const el=id=>document.getElementById(id);
  let kind='RECEIPT';
  const hints={RECEIPT:'',TAX_INVOICE:''};
  const text={
    RECEIPT:{name:'영수증',title:'사진 한 장을\n경비 한 줄로.',help:'영수증 사진 또는 PDF를 올리세요.\n거래처·날짜·금액을 확인한 뒤 장부에 확정합니다.',hint:'예: 직원 점심 식사, 거래처 미팅, 사무용품 구매'},
    TAX_INVOICE:{name:'세금계산서',title:'공급자와 품목을\n한 장씩 정확하게.',help:'세금계산서 사진 또는 PDF를 올리세요.\n공급자·공급받는 자·품목·문서 합계를 전용 폼에서 대조합니다.',hint:'예: 업무용 복사용지 및 사무용품 구입'}
  };
  function select(value){
    if(uploading)return;
    if(value!==kind){hints[kind]=el('upload-hint').value;el('upload-hint').value=hints[value];}
    kind=value;const selected=text[kind];
    for(const button of document.querySelectorAll('[data-document-kind]')){
      const active=button.dataset.documentKind===kind;button.setAttribute('aria-selected',String(active));button.tabIndex=active?0:-1;
    }
    el('document-upload-panel').setAttribute('aria-labelledby',kind==='RECEIPT'?'receipt-tab':'invoice-tab');
    el('upload-kind-label').textContent='01 · '+selected.name+' 등록';el('upload-title').textContent=selected.title;
    el('upload-help').textContent=selected.help;el('upload-hint').placeholder=selected.hint;
    el('upload-limit').textContent=`JPG·PNG 10MB / PDF 25MB·최대 20쪽 · 한 페이지에 ${selected.name} 한 장`;
    el('upload-label').textContent=selected.name+' 사진·PDF를 끌어 놓거나 선택하세요';
    el('upload-choose').textContent=selected.name+' 사진·PDF 선택';el('manual-button').textContent='+ '+selected.name+' 직접 입력';
  }
  function busy(value){for(const button of document.querySelectorAll('[data-document-kind]'))button.disabled=value;}
  function resetLabel(){el('upload-label').textContent=text[kind].name+' 사진·PDF를 끌어 놓거나 선택하세요';}
  function init(){
    const tabs=Array.from(document.querySelectorAll('[data-document-kind]'));
    for(const [index,tab] of tabs.entries()){
      tab.addEventListener('click',()=>select(tab.dataset.documentKind));
      tab.addEventListener('keydown',event=>{
        if(!['ArrowLeft','ArrowRight','Home','End'].includes(event.key)||uploading)return;
        event.preventDefault();const next=event.key==='Home'?0:event.key==='End'?tabs.length-1:(index+(event.key==='ArrowRight'?1:-1)+tabs.length)%tabs.length;
        select(tabs[next].dataset.documentKind);tabs[next].focus();
      });
    }
    select('RECEIPT');
  }
  return {init,kind:()=>kind,label:()=>text[kind].name,busy,resetLabel};
})();
