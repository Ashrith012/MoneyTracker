export type SmsConfig={provider:'twilio'|'webhook',endpoint?:string,key?:string,template?:string,account?:string,token?:string,service?:string,body?:string};
export const validPhone=(value:string)=>/^\+91[6-9]\d{9}$/.test(value);
export async function sendCode(config:SmsConfig,to:string,code:string,fetcher:typeof fetch=fetch):Promise<void> {
 if(!validPhone(to)||!/^\d{6}$/.test(code))throw new Error('Invalid SMS parameters');
 let url:string;let headers:Record<string,string>;let body:string;
 if(config.provider==='twilio') {
  if(!config.account?.match(/^AC[a-fA-F0-9]{32}$/)||!config.token||!config.service||!config.body?.includes('{code}'))throw new Error('SMS configuration incomplete');
  url=`https://api.twilio.com/2010-04-01/Accounts/${config.account}/Messages.json`;
  headers={'Authorization':`Basic ${Buffer.from(`${config.account}:${config.token}`).toString('base64')}`,'Content-Type':'application/x-www-form-urlencoded'};
  body=new URLSearchParams({To:to,MessagingServiceSid:config.service,Body:config.body.replaceAll('{code}',code)}).toString();
 } else {
  if(!config.endpoint?.startsWith('https://')||!config.key||!config.template)throw new Error('SMS configuration incomplete');
  url=config.endpoint;headers={'Authorization':`Bearer ${config.key}`,'Content-Type':'application/json'};
  body=JSON.stringify({to,templateId:config.template,variables:{code}});
 }
 const response=await fetcher(url,{method:'POST',headers,body,signal:AbortSignal.timeout(10000)});
 if(!response.ok)throw new Error('OTP delivery failed');
}
