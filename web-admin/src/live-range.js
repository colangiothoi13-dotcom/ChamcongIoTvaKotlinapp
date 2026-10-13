// A bounded live snapshot may omit older paginated records. Never discard them.
// Ambiguous removals invalidate calculations until a fresh full read resolves them.
export function mergeRangeSnapshot(previous,incoming,{hasMore=false,previousVisible=null}={}) {
  const ids=new Set(incoming.map(row=>row.id));
  const ambiguousRemoval=hasMore&&previousVisible!==null&&[...previousVisible].some(id=>!ids.has(id));
  if(!hasMore)return {rows:incoming,visible:ids,needsReload:false};
  return {rows:[...incoming,...previous.filter(row=>!ids.has(row.id))],visible:ids,needsReload:ambiguousRemoval};
}
