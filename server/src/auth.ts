import { betterAuth } from 'better-auth';
import { phoneNumber, bearer } from 'better-auth/plugins';
import pg from 'pg';
import { sendCode, validPhone } from './sms.js';
export function required(name:string):string {
 const value=process.env[name]; if(!value) throw new Error(`Missing ${name}`); return value;
}
export const pool=new pg.Pool({connectionString:required('DATABASE_URL'),max:5});
const baseURL=required('BETTER_AUTH_URL');
if(!baseURL.startsWith('https://')) throw new Error('HTTPS API URL required');
const secret=required('BETTER_AUTH_SECRET');
if(secret.length<32||secret.includes('REPLACE')) throw new Error('Auth secret must contain at least 32 characters');
export const auth=betterAuth({
 database:pool,baseURL,secret,
 rateLimit:{enabled:true,storage:'database',window:60,max:30},
 plugins:[bearer(),phoneNumber({
  phoneNumberValidator:validPhone,
  otpLength:6,expiresIn:300,allowedAttempts:3,
  signUpOnVerification:{getTempEmail:(number)=>`${number.slice(1)}@phone.invalid`,getTempName:()=> 'MoneyTrack user'},
  sendOTP:async ({phoneNumber:recipient,code})=>sendCode({
   provider:process.env.SMS_PROVIDER==='twilio'?'twilio':'webhook',
   endpoint:process.env.SMS_ENDPOINT,key:process.env.SMS_API_KEY,template:process.env.SMS_TEMPLATE_ID,
   account:process.env.TWILIO_ACCOUNT_SID,token:process.env.TWILIO_AUTH_TOKEN,
   service:process.env.TWILIO_MESSAGING_SERVICE_SID,body:process.env.TWILIO_OTP_BODY
  },recipient,code)
 })]
});
