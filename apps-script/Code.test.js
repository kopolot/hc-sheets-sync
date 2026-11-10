// Offline test of Code.gs against a mocked SpreadsheetApp. Run: node apps-script/Code.test.js
const fs = require('fs'), vm = require('vm'), assert = require('assert');
function mockSheet(name){ const data=[]; const s={name,data,
  getLastRow:()=>data.length, getMaxRows:()=>1000, setFrozenRows(){},
  getRange(r,c,nr=1,nc=1){ return {
    setValues(v){ v.forEach((row,i)=>{ data[r-1+i]=data[r-1+i]||[]; row.forEach((x,j)=>data[r-1+i][c-1+j]=x);}); return this;},
    getValues(){ const out=[]; for(let i=0;i<nr;i++){const row=[];for(let j=0;j<nc;j++)row.push((data[r-1+i]||[])[c-1+j]??'');out.push(row);} return out;},
    clearContent(){ for(let i=0;i<nr;i++) for(let j=0;j<nc;j++) if(data[r-1+i]) data[r-1+i][c-1+j]=''; while(data.length && data[data.length-1].every(x=>x===''))data.pop(); return this;},
    setFontWeight(){return this;}, setNumberFormat(){return this;} };}}; return s;}
const sheets={}; const ss={getSheetByName:n=>sheets[n], insertSheet:n=>(sheets[n]=mockSheet(n)), getSpreadsheetTimeZone:()=>'Europe/Warsaw'};
let secret='abc';
const ctx={ SpreadsheetApp:{getActive:()=>ss}, PropertiesService:{getScriptProperties:()=>({getProperty:()=>secret})},
  LockService:{getScriptLock:()=>({waitLock(){},releaseLock(){}})}, Utilities:{formatDate:()=>'X'},
  ContentService:{MimeType:{JSON:1}, createTextOutput:t=>({setMimeType(){return JSON.parse(t);}})}, Date };
vm.createContext(ctx); vm.runInContext(fs.readFileSync(require('path').join(__dirname,'Code.gs'),'utf8'), ctx);
const post=b=>ctx.doPost({postData:{contents:JSON.stringify(b)}});
const day=(date,extra={})=>Object.assign({date,kcal_in:2000,protein_g:150,fat_g:null,carbs_g:200,kcal_burned_total:null,kcal_active:300,steps:9000,resting_hr:55,weight_kg:82.1,body_fat_pct:null,sleep_min:420,workouts:[]},extra);
const w=(id,start)=>({id,type:'strength_training',title:null,start,end:start,min:60});

assert.deepStrictEqual(post({secret:'nope',days:[]}), {ok:false,error:'bad secret'});
let r=post({secret:'abc',days:[day('2026-11-09',{workouts:[w('a','2026-11-09T06:00:00Z'),w('b','2026-11-09T17:00:00Z')]}), day('2026-11-10')]});
assert.strictEqual(r.ok,true); assert.strictEqual(r.workouts,2);
const D=sheets.daily.data, W=sheets.workouts.data;
assert.strictEqual(D.length,3); assert.strictEqual(D[1][12],2); assert.strictEqual(D[1][13],120); assert.strictEqual(D[1][3],'');
assert.strictEqual(D[1][14],'strength_training 60min; strength_training 60min');
// resend same + update kcal + workout b deleted in HC + older day appended
r=post({secret:'abc',days:[day('2026-11-08'), day('2026-11-09',{kcal_in:2500,workouts:[w('a','2026-11-09T06:00:00Z')]}), day('2026-11-10')]});
assert.strictEqual(D.length,4, 'no duplicates');
assert.deepStrictEqual(D.slice(1).map(x=>x[0]),['2026-11-08','2026-11-09','2026-11-10'],'sorted');
assert.strictEqual(D[2][1],2500);
assert.strictEqual(W.length,2); assert.strictEqual(W[1][0],'a');
// workouts of dates not in payload are kept
post({secret:'abc',days:[day('2026-11-10',{workouts:[w('c','2026-11-10T06:00:00Z')]})]});
assert.deepStrictEqual(W.slice(1).map(x=>x[0]),['a','c']);
secret=null; assert.strictEqual(post({secret:'abc',days:[]}).ok,false);
assert.strictEqual(post({secret:'abc'}).ok,false);
console.log('apps script tests ok');
