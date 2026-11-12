import express from 'express';
import helmet from 'helmet';
import { rateLimit } from 'express-rate-limit';
import { toNodeHandler, fromNodeHeaders } from 'better-auth/node';
import { auth, pool } from './auth.js';
const app=express();
app.disable('x-powered-by');
app.set('trust proxy',1);
app.use(helmet());
app.use(rateLimit({windowMs:60000,limit:60,standardHeaders:'draft-8',legacyHeaders:false}));
app.get('/health',(_req,res)=>res.json({ok:true}));
app.all('/api/auth/*splat',toNodeHandler(auth));
app.use(express.json({limit:'8kb'}));
app.post('/api/consents',async(req,res)=>{
 const session=await auth.api.getSession({headers:fromNodeHeaders(req.headers)});
 if(!session){res.sendStatus(401);return;}
 const {kind,granted,version}=req.body;
 if(!['gmail','analytics','privacy'].includes(kind)||typeof granted!=='boolean'||version!=='1') {res.sendStatus(400);return;}
 await pool.query('INSERT INTO consent_records(user_id,kind,granted,version) VALUES($1,$2,$3,$4)',[session.user.id,kind,granted,version]);
 res.sendStatus(204);
});
app.delete('/api/profile',async(req,res)=>{
 const session=await auth.api.getSession({headers:fromNodeHeaders(req.headers)});
 if(!session){res.sendStatus(401);return;}
 // Removing the Better Auth user cascades sessions and consent rows.
 await pool.query('DELETE FROM "user" WHERE id=$1',[session.user.id]);
 res.sendStatus(204);
});
app.use((_err:unknown,_req:express.Request,res:express.Response,_next:express.NextFunction)=>res.status(500).json({error:'Request failed'}));
app.listen(Number(process.env.PORT||8080),'0.0.0.0');
