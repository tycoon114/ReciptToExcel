// 국세청 조회는 저장된 판매점 번호에만 실행합니다. API 키와 과세유형 입력값은 브라우저가 보내지 않습니다.
window.NtsUI=(()=>{
  let source=null, busy=false;
  const el=id=>document.getElementById(id);
  const dirty=()=>source && fields.some(field=>{
    const value=el("edit-"+field).value.trim();
    return ["amount","supplyAmount","vatAmount"].includes(field)
      ? (value===""?null:Number(value))!==(source[field]??null) : value!==String(source[field]??"");
  });
  function controls(){
    const button=el("nts-lookup");if(!source)return;
    button.disabled=busy || photoBusy || el("edit-businessNumber").disabled;
    button.textContent=busy?"국세청 조회 중…":config?.ntsConfigured?"국세청 조회 · 새로 조회":"국세청 연결 방법";
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
    if(!config?.ntsConfigured)details.push("인증키 설정 후 앱을 다시 시작하면 조회할 수 있습니다.");
    el("nts-details").textContent=details.join(" · ");
    const review=expense.vatReview;
    el("vat-review-result").textContent=review?.label||"확인 필요";
    el("vat-review-result").className="vat-"+(["CANDIDATE","NON_DEDUCTIBLE","NO_VAT","REVIEW","EXCLUDED"].includes(review?.code)?review.code:"REVIEW");
    el("vat-review-reason").textContent=review?.reason||"새 버전으로 앱을 재시작한 뒤 저장된 내용을 확인하세요.";
    el("nts-error").hidden=true;controls();
  }
  async function lookup(){
    if(busy||photoBusy||!source)return;
    if(!config.ntsConfigured){el("guide").showModal();return;}
    const number=el("edit-businessNumber").value.replaceAll("-","");
    if(!/^[0-9]{10}$/.test(number)){showError("영수증 원본의 판매점 사업자등록번호를 입력하고 먼저 저장해 주세요.");return;}
    if(dirty()){showError("수정한 내용이 있습니다. 먼저 저장한 뒤 다시 열어 조회해 주세요.");return;}
    busy=true;const elements=Array.from(el("editor").querySelectorAll("button,input,select,textarea"));
    const previous=elements.map(node=>node.disabled);elements.forEach(node=>node.disabled=true);
    el("nts-lookup").textContent="국세청 조회 중…";el("nts-error").hidden=true;
    try{
      const updated=await api(`/api/expenses/${source.id}/business-status`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({version:source.version})});
      openEditor(updated);await load();notice("국세청 사업자 상태를 조회했습니다. 공제·환급 여부를 확정한 결과는 아닙니다.");
    }catch(error){showError(error.message);}
    finally{busy=false;elements.forEach((node,i)=>node.disabled=previous[i]);BookkeepingUI.applyLocks();controls();}
  }
  function showError(message){el("nts-error").textContent=message;el("nts-error").hidden=false;}
  function init(){el("nts-lookup").addEventListener("click",lookup);}
  return {open,init,applyLocks:controls,isBusy:()=>busy};
})();
