import {test} from 'node:test';
import assert from 'node:assert/strict';
import {sendCode,validPhone} from './sms.js';
test('only accepts normalized Indian mobile numbers',()=>{
 assert.equal(validPhone('+919876543210'),true);
 for(const phone of ['9876543210','+911234567890','+441234567890','+9198765432100'])assert.equal(validPhone(phone),false);
});
test('delivers Better Auth code through configured HTTPS gateway',async()=>{
 let calls=0;
 const fake:typeof fetch=async(url,options)=>{
  calls++;assert.equal(url,'https://sms.example.test/send');
  const body=JSON.parse(options!.body as string);assert.equal(body.variables.code,'638291');assert.equal(body.to,'+919876543210');
  return new Response('',{status:202});
 };
 await sendCode({provider:'webhook',endpoint:'https://sms.example.test/send',key:'test-only',template:'otp'},'+919876543210','638291',fake);
 assert.equal(calls,1);
});
test('rejects plaintext SMS endpoint before any request',async()=>{
 await assert.rejects(sendCode({provider:'webhook',endpoint:'http://example.test',key:'x',template:'otp'},'+919876543210','638291',async()=>{throw new Error('must not be called')}),/configuration/);
});
test('failed delivery is not reported as success',async()=>{
 await assert.rejects(sendCode({provider:'webhook',endpoint:'https://example.test',key:'x',template:'otp'},'+919876543210','638291',async()=>new Response('',{status:503})),/delivery failed/);
});
test('Twilio request uses approved template substitution',async()=>{
 await sendCode({provider:'twilio',account:'AC'+'a'.repeat(32),token:'test-only',service:'MGtest',body:'Your app code is {code}.'},'+919876543210','638291',async(_url,options)=>{
  assert.equal(new URLSearchParams(options!.body as string).get('Body'),'Your app code is 638291.');
  return new Response('',{status:201});
 });
});
