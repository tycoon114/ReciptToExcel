// 국세청 전용 설정 모달입니다. 저장된 키는 서버에서 다시 읽어오지 않습니다.
window.NtsSettingsUI=(()=>{
  const el=id=>document.getElementById(id);
  let busy=false;
  function message(text,error=false){el("nts-settings-message").textContent=text;el("nts-settings-message").classList.toggle("form-error",error);}
  function lock(value){busy=value;el("nts-settings").querySelectorAll("button,input").forEach(node=>node.disabled=value);}
  async function open(){
    if(busy)return;
    el("nts-key").value="";el("nts-test-number").value=el("editor").open?el("edit-businessNumber").value:"";
    message("");el("nts-settings").showModal();
    try{const status=await api("/api/nts-settings");el("nts-key-state").textContent=status.configured?"인증키가 설정되어 있습니다. 새 키를 입력하면 교체됩니다.":"아직 인증키가 설정되지 않았습니다.";}
    catch(e){message(e.message,true);}
  }
  async function submit(test){
    if(busy)return;
    const key=el("nts-key").value.trim(),businessNumber=el("nts-test-number").value.trim();
    if(!test&&!key){message("저장할 인증키를 입력해 주세요.",true);return;}
    if(test&&!/^(?:[0-9]{10}|[0-9]{3}-[0-9]{2}-[0-9]{5})$/.test(businessNumber)){message("연결 확인에 사용할 실제 사업자번호를 입력해 주세요.",true);return;}
    lock(true);message(test?"국세청 연결 확인 중…":"인증키 저장 중…");
    try{
      const result=await api("/api/nts-settings"+(test?"/test":""),{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({key,businessNumber})});
      if(test)message(result.message+(key?" 입력한 키는 아직 저장되지 않았습니다.":""));
      else{config.ntsConfigured=result.configured;el("nts-key").value="";el("nts-key-state").textContent="인증키가 설정되어 있습니다. 새 키를 입력하면 교체됩니다.";NtsUI.settingsChanged();message("저장했습니다. 재시작 없이 국세청 조회에 적용됩니다.");}
    }catch(e){message(e.message,true);}finally{lock(false);}
  }
  function init(){
    el("nts-settings-button").addEventListener("click",open);
    el("close-nts-settings").addEventListener("click",()=>el("nts-settings").close());
    el("nts-settings").addEventListener("cancel",e=>{if(busy)e.preventDefault();});
    el("nts-settings").addEventListener("close",()=>{el("nts-key").value="";});
    el("nts-settings-form").addEventListener("submit",e=>{e.preventDefault();submit(false);});
    el("nts-test").addEventListener("click",()=>submit(true));
  }
  return {open,init};
})();
