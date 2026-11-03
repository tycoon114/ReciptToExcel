"use strict";

// 문서 합계와 품목을 분리해 편집합니다. 빈 세액을 수량×단가나 10% 계산으로 채우지 않습니다.
window.InvoiceUI=(()=>{
  const el=id=>document.getElementById(id);
  const columns=[['date','품목일자','text',10],['name','품목','text',200],['specification','규격','text',100],
    ['quantity','수량','number'],['unitPrice','단가','number'],['supplyAmount','공급가액','number'],['taxAmount','세액','number']];
  let active=false,warning='',baseline='null',locked=false;
  let receiptNodes=[],receiptLabels=[];
  /** 입력 노드는 한 개만 유지합니다. 영수증은 원래 순서로, 세금계산서는 전용 구획으로 배치합니다. */
  function layout(){
    const receipt=el('receipt-fields');for(const node of receiptNodes)receipt.append(node);
    for(const [node,text] of receiptLabels)node.nodeValue=text;
    receipt.hidden=active;el('invoice-panel').hidden=!active;
    el('editor').classList.toggle('invoice-editor',active);
    el('editor-title').textContent=active?'세금계산서 검토 · 기장':'영수증 검토 · 기장';
    el('editor-eyebrow').textContent=active?'TAX INVOICE REVIEW':'RECEIPT REVIEW';
    el('preview-title').textContent=active?'세금계산서 원본':'영수증 원본';
    el('editor-required-note').textContent=active?'* 확정 시 필수 항목 · 품목과 문서 합계, 업무 목적을 대조한 뒤 확정하세요.':'* 확정 시 필수 항목 · 영수증 확정 후 전표 기장은 아래에서 별도로 진행합니다. 세액은 임의로 계산하지 않습니다.';
    if(!active)return;
    const move=(target,fields)=>{for(const field of fields)el(target).append(el('edit-'+field).closest('label'));};
    move('invoice-supplier-fields',['vendor','businessNumber','vendorAddress']);
    el('invoice-date-fields').prepend(el('edit-receiptDate').closest('label'));
    move('invoice-total-fields',['amount','supplyAmount','vatAmount']);
    move('invoice-purpose-fields',['description','category','paymentMethod','ownerName','department','note']);
    el('invoice-review-fields').append(el('nts-panel'));
    for(const [field,label] of Object.entries({vendor:'공급자 상호 *',businessNumber:'공급자 사업자등록번호',vendorAddress:'공급자 주소 (선택)',receiptDate:'작성일 *',description:'업무 목적(적요) *',amount:'합계금액 (원) *',vatAmount:'세액 (원)'})){
      el('edit-'+field).closest('label').firstChild.nodeValue=label;
    }
  }
  function add(item={}){
    if(el('invoice-items').children.length>=100){alert('품목은 최대 100줄까지 입력할 수 있습니다.');return;}
    const row=document.createElement('tr');
    for(const [key,label,type,max] of columns){
      const td=document.createElement('td'),input=document.createElement('input');input.dataset.field=key;
      input.type=type;input.setAttribute('aria-label',label);input.value=item[key]??'';input.disabled=locked;
      if(max)input.maxLength=max;
      if(type==='number'){input.min='0';input.max='999999999999';input.step=['quantity','unitPrice'].includes(key)?'0.000001':'1';}
      td.append(input);row.append(td);
    }
    const td=document.createElement('td'),remove=document.createElement('button');remove.type='button';remove.className='text-button';remove.textContent='삭제';remove.disabled=locked;
    remove.addEventListener('click',()=>row.remove());td.append(remove);row.append(td);el('invoice-items').append(row);
  }
  function value(){
    if(!active)return null;
    return {issueNumber:el('invoice-number').value.trim(),buyerBusinessNumber:el('invoice-buyer-number').value.trim(),
      buyerName:el('invoice-buyer-name').value.trim(),warning,
      items:Array.from(el('invoice-items').children).map(row=>Object.fromEntries(Array.from(row.querySelectorAll('input')).map(input=>{
        const key=input.dataset.field,text=input.value.trim();
        return [key,input.type==='number'?(text===''?null:['supplyAmount','taxAmount'].includes(key)?Number(text):text):text];
      })))};
  }
  function open(expense){
    active=!!expense.taxInvoice;warning=expense.taxInvoice?.warning||'';locked=false;
    layout();
    el('invoice-number').value=expense.taxInvoice?.issueNumber||'';
    el('invoice-buyer-number').value=expense.taxInvoice?.buyerBusinessNumber||'';
    el('invoice-buyer-name').value=expense.taxInvoice?.buyerName||'';
    el('invoice-warning').textContent=warning?'인식 시 안내: '+warning:'';el('invoice-items').replaceChildren();
    for(const item of expense.taxInvoice?.items||[])add(item);
    baseline=JSON.stringify(value());
  }
  function applyLocks(value){
    locked=value;document.querySelectorAll('#invoice-panel input,#invoice-panel button').forEach(node=>node.disabled=value||!active);
    // 세금계산서 폼에 표시하지 않는 영수증 항목은 브라우저의 숨은 필드 검증에서도 제외합니다.
    for(const field of ['receiptTime','approvalNumber'])el('edit-'+field).disabled=value||active;
  }
  function init(){
    receiptNodes=Array.from(el('receipt-fields').children);
    receiptLabels=Array.from(el('receipt-fields').querySelectorAll('label')).map(label=>[label.firstChild,label.firstChild.nodeValue]);
    el('invoice-add-row').addEventListener('click',()=>add());
  }
  return {open,value,init,applyLocks,isDirty:()=>JSON.stringify(value())!==baseline};
})();
