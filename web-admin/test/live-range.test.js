import test from 'node:test';
import assert from 'node:assert/strict';
import {mergeRangeSnapshot} from '../src/live-range.js';
test('initial 500-record live shell preserves a completely paginated month and permits calculations',()=>{const complete=Array.from({length:620},(_,id)=>({id:String(id),value:id}));const result=mergeRangeSnapshot(complete,complete.slice(0,500),{hasMore:true});assert.equal(result.rows.length,620);assert.equal(result.needsReload,false);});
test('an ambiguous limit eviction blocks calculation until full reload instead of presenting stale totals',()=>{const previous=[{id:'newest'},{id:'second'},{id:'older'}];const next=mergeRangeSnapshot(previous,[{id:'new'},{id:'newest'}],{hasMore:true,previousVisible:new Set(['newest','second'])});assert.equal(next.needsReload,true);assert.equal(next.rows.length,4);});
test('unbounded completed snapshot safely applies deletions and edits',()=>{const result=mergeRangeSnapshot([{id:'keep',name:'Old'},{id:'deleted'}],[{id:'keep',name:'New'}],{hasMore:false});assert.deepEqual(result.rows,[{id:'keep',name:'New'}]);assert.equal(result.needsReload,false);});
