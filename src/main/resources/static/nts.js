// 국세청 조회는 저장된 판매점 번호에만 실행합니다. API 키와 과세유형 입력값은 브라우저가 보내지 않습니다.
window.NtsUI=(()=>{
  let source=null, busy=false;
  const el=id=>document.getElementById(id);
  const dirty=()=>source && (!!window.InvoiceUI?.isDirty() || fields.some(field=>{
    const value=el("edit-"+field).value.trim();
    return ["amount","supplyAmount","vatAmount"].includes(field)
      ? (value===""?null:Number(value))!==(source[field]??null) : value!==String(source[field]??"");
  }));
  function controls(){
    const button=el("nts-lookup");if(!source)return;
    button.disabled=busy || editorSaving || photoBusy || el("edit-businessNumber").disabled;
    button.textContent=busy?"국세청 조회 중…":config?.ntsConfigured?dirty()?"저장 후 국세청 조회":"국세청 조회 · 새로 조회":"국세청 연결 방법";
  }
  function open(expense){
    source=expense;
    const result=expense.businessLookup;
    const valid=result && result.businessNumber===String(expense.businessNumber||"").replaceAll("-","");
    el("nts-result").textContent=valid?`${result.taxType} / ${result.businessState}`:"미조회";
    const details=[];
    if(valid){
      details.push(`조회 시각: ${new Date(result.checkedAt).toLocaleString("ko-KR")}`);
      if(result.closedOn)details.push(`폐업일: ${result.closedOn}`);
      if(result.taxTypeChangedOn)details.push(`최근 과세유형 전환일: ${result.taxTypeChangedOn}`);
      if(result.previousTaxType && result.previousTaxType!=="해당없음")details.push(`직전 과세유형: ${result.previousTaxType}`);
      if(result.invoiceAppliedOn)details.push(`세금계산서 적용일: ${result.invoiceAppliedOn}`);
    }
    if(!config?.ntsConfigured)details.push("국세청 연결 설정에서 인증키를 저장하면 조회할 수 있습니다.");
    el("nts-details").textContent=details.join(" · ");
    const review=expense.vatReview;
    el("vat-review-result").textContent=review?.label||"확인 필요";
    el("vat-review-result").className="vat-"+(["CANDIDATE","NON_DEDUCTIBLE","NO_VAT","REVIEW","EXCLUDED"].includes(review?.code)?review.code:"REVIEW");
    el("vat-review-reason").textContent=review?.reason||"새 버전으로 앱을 재시작한 뒤 저장된 내용을 확인하세요.";
    el("nts-error").hidden=true;controls();
  }
  async function lookup(){
    if(busy||editorSaving||photoBusy||BookkeepingUI.isBusy()||DuplicateUI.isBusy()||!source)return;
    if(!config.ntsConfigured){NtsSettingsUI.open();return;}
    const number=el("edit-businessNumber").value.replaceAll("-","");
    if(!/^[0-9]{10}$/.test(number)){showError("영수증 원본의 판매점 사업자등록번호 10자리를 입력해 주세요.");return;}
    const needsSave=dirty();
    if(needsSave&&!el("edit-form").reportValidity())return;
    let savedBeforeLookup=false;
    busy=true;const elements=Array.from(el("editor").querySelectorAll("button,input,select,textarea"));
    const previous=elements.map(node=>node.disabled);elements.forEach(node=>node.disabled=true);
    el("nts-lookup").textContent="국세청 조회 중…";el("nts-error").hidden=true;
    try{
      if(needsSave){
        el("nts-lookup").textContent="변경사항 저장 중…";
        await saveEditor(source.status);savedBeforeLookup=true;
        el("nts-lookup").textContent="국세청 조회 중…";
      }
      const updated=await api(`/api/expenses/${source.id}/business-status`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({version:source.version})});
      openEditor(updated);await load();notice("국세청 사업자 상태를 조회했습니다. 공제·환급 여부를 확정한 결과는 아닙니다.");
    }catch(error){
      showError((savedBeforeLookup?"변경사항은 저장되었습니다. ":"")+error.message);
      if(savedBeforeLookup)try{await load();}catch(listError){notice("목록을 갱신하지 못했습니다. "+listError.message,true);}
    }
    finally{busy=false;elements.forEach((node,i)=>node.disabled=previous[i]);BookkeepingUI.applyLocks();DuplicateUI.applyLocks();controls();}
  }
  function showError(message){el("nts-error").textContent=message;el("nts-error").hidden=false;}
  function init(){el("nts-lookup").addEventListener("click",lookup);el("edit-form").addEventListener("input",controls);el("edit-form").addEventListener("change",controls);}
  return {open,init,applyLocks:controls,settingsChanged:()=>{if(source)open(source);},isBusy:()=>busy};
})();
