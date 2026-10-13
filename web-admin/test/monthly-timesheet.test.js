import test from 'node:test';
import assert from 'node:assert/strict';
import {at,monthlyTimesheetRows,monthlyTimesheetEmployees,monthlyTotals,reportRows} from '../src/domain.js';
import {onAction,render} from '../src/attendance.js';
import {memoryStore} from '../src/demo.js';

test('monthly timesheet includes the departure day, excludes later source days and keeps payroll/history scope',()=>{
  const employee={id:'e1',fullName:'An',active:false,terminationDate:'2026-10-10'};
  const attendance=['2026-10-09','2026-10-10','2026-10-11'].flatMap(date=>[
    {id:date+'-in',employeeId:'e1',timestamp:at(date,'08:00'),type:'CHECK_IN',status:'NORMAL',resolutionStatus:'ACCEPTED',verified:true},
    {id:date+'-out',employeeId:'e1',timestamp:at(date,'12:00'),type:'CHECK_OUT',status:'NORMAL',resolutionStatus:'ACCEPTED',verified:true}
  ]);
  const data={employees:[employee],attendance}; const snapshot=structuredClone(data);
  const now=at('2026-11-01','09:00');
  assert.deepEqual(monthlyTimesheetRows(data,'2026-10',[employee],now).map(row=>row.date),['2026-10-09','2026-10-10']);
  assert.equal(reportRows(data,'2026-10-01','2026-10-31',[employee],now).length,3);
  assert.equal(monthlyTotals(data,'2026-10',[employee],now).get('e1').hoursWorked,12);
  assert.deepEqual(data,snapshot);
  assert.equal(monthlyTimesheetRows(data,'2026-10',[{...employee,terminationDate:'invalid'}],now).length,3);
});

function legacyData(terminationDate) {
  const employee={id:'legacy',code:'OLD1',fullName:'Legacy Retired',active:false,...(terminationDate===undefined?{}:{terminationDate})};
  const attendance=['2026-09-10','2026-10-10','2026-11-10'].flatMap(date=>[
    {id:date+'-in',employeeId:employee.id,timestamp:at(date,'08:00'),type:'CHECK_IN',status:'NORMAL',resolutionStatus:'ACCEPTED',verified:true},
    {id:date+'-out',employeeId:employee.id,timestamp:at(date,'12:00'),type:'CHECK_OUT',status:'NORMAL',resolutionStatus:'ACCEPTED',verified:true}
  ]);
  return {employees:[employee],attendance};
}

for(const terminationDate of [undefined,'invalid']) {
  test(`legacy inactive ${terminationDate===undefined?'missing':'invalid'} departure keeps historical/current evidence but hides future months`,()=>{
    const data=legacyData(terminationDate),before=structuredClone(data),now=at('2026-10-11','09:00');
    assert.deepEqual(monthlyTimesheetEmployees(data,'2026-09',data.employees,now).map(e=>e.id),['legacy']);
    assert.deepEqual(monthlyTimesheetEmployees(data,'2026-10',data.employees,now).map(e=>e.id),['legacy']);
    assert.deepEqual(monthlyTimesheetEmployees(data,'2026-11',data.employees,now),[]);
    assert.deepEqual(data,before);
  });
}

test('legacy eligibility needs meaningful monthly evidence; rejected scans cannot keep a profile visible',()=>{
  const data=legacyData(undefined),now=at('2026-10-11','09:00');
  assert.deepEqual(monthlyTimesheetEmployees({...data,attendance:[]},'2026-10',data.employees,now),[]);
  assert.deepEqual(monthlyTimesheetEmployees({...data,attendance:data.attendance.map(row=>({...row,verified:false}))},'2026-10',data.employees,now),[]);
  assert.deepEqual(monthlyTimesheetEmployees({...data,attendance:[data.attendance.find(row=>row.id==='2026-10-10-in')]},'2026-10',data.employees,now).map(e=>e.id),['legacy']);
});

test('a registered shift is legacy evidence even before punches arrive, with current month measured in Vietnam',()=>{
  const employee={id:'legacy',active:false},date='2026-11-01';
  const data={employees:[employee],shifts:[{id:'am',name:'Sáng',category:'MORNING',startTime:'08:00',endTime:'12:00',active:true}],workSchedules:[{id:'legacy_'+date,employeeId:'legacy',date,shiftId:'am'}]};
  const now=Date.parse('2026-10-31T17:30:00Z'); // 00:30 on November 1 in Vietnam.
  assert.deepEqual(monthlyTimesheetEmployees(data,'2026-11',[employee],now).map(e=>e.id),['legacy']);
  assert.deepEqual(monthlyTimesheetEmployees(data,'2026-12',[employee],now),[]);
});

test('valid recorded departure hides later months even when the active flag remains true',()=>{
  const departed={id:'past',active:true,terminationDate:'2026-09-30'},active={id:'active',active:true};
  const data={employees:[departed,active]},now=at('2026-10-11','09:00');
  assert.deepEqual(monthlyTimesheetEmployees(data,'2026-09',data.employees,now).map(e=>e.id),['past','active']);
  assert.deepEqual(monthlyTimesheetEmployees(data,'2026-10',data.employees,now).map(e=>e.id),['active']);
  assert.deepEqual(monthlyTimesheetEmployees(data,'2026-11',data.employees,now).map(e=>e.id),['active']);
});

test('MONTHLY main, details and CSV use the same eligibility without exposing empty legacy or later-month departed profiles',async t=>{
  const now=at('2026-10-11','09:00'); t.mock.method(Date,'now',()=>now);
  const data=legacyData(undefined);
  data.employees.push({id:'empty',code:'EMPTY',fullName:'Empty Retired',active:false},
    {id:'past',code:'PAST',fullName:'Active Past Departure',active:true,terminationDate:'2026-09-30'});
  let dialog,csvBlob;
  const ctx={data,store:memoryStore(data),filters:{month:'2026-10',reportType:'MONTHLY'},loaded:{employees:true},errors:{},range:{from:'2026-10-01',to:'2026-10-31',complete:true,loading:false},ui:{dialog:value=>{dialog=value;}},loadRange:async()=>{}};
  const body=render('reports',ctx),tableBody=body.split('<tbody>')[1].split('</tbody>')[0];
  assert.match(tableBody,/Legacy Retired/);assert.doesNotMatch(tableBody,/Empty Retired|Active Past Departure/);
  await onAction('timesheet-detail',{dataset:{id:'legacy'}},ctx);
  assert.match(dialog.body,/Legacy Retired/);assert.match(dialog.body,/Giờ thường 4 h/);
  await assert.rejects(onAction('timesheet-detail',{dataset:{id:'empty'}},ctx),/không thuộc kỳ/);
  await assert.rejects(onAction('timesheet-detail',{dataset:{id:'past'}},ctx),/không thuộc kỳ/);
  const previousDocument=globalThis.document;
  globalThis.document={createElement:()=>({click(){}})};
  t.after(()=>{if(previousDocument===undefined)delete globalThis.document;else globalThis.document=previousDocument;});
  t.mock.method(URL,'createObjectURL',blob=>{csvBlob=blob;return 'blob:test';});
  t.mock.method(URL,'revokeObjectURL',()=>{});t.mock.method(globalThis,'setTimeout',fn=>{fn();return 0;});
  await onAction('reports-export',{dataset:{}},ctx);
  const csvText=await csvBlob.text();
  assert.match(csvText,/Legacy Retired/);assert.doesNotMatch(csvText,/Empty Retired|Active Past Departure/);
});
