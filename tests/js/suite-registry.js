'use strict'

/** Discovered tests must be registered once or explicitly superseded with a reason. */
function registryProblems(suites, excluded, discovered, exists) {
  const errors=[], files=suites.map(s=>s.file), omissions=excluded.map(s=>s.file)
  for(const file of new Set(files))if(files.filter(f=>f===file).length!==1)errors.push('duplicate suite: '+file)
  for(const file of new Set(omissions))if(omissions.filter(f=>f===file).length!==1)errors.push('duplicate exclusion: '+file)
  for(const row of excluded) {
    if(typeof row.reason!=='string'||!row.reason.trim())errors.push('unexplained exclusion: '+row.file)
    if(files.includes(row.file))errors.push('registered and excluded: '+row.file)
  }
  for(const file of discovered)if(!files.includes(file)&&!omissions.includes(file))errors.push('unregistered suite: '+file)
  for(const file of [...files,...omissions]) {
    if(!exists(file))errors.push('missing suite: '+file)
    if(file.endsWith('.test.js')&&!file.startsWith('../')&&!discovered.includes(file))errors.push('not discovered: '+file)
  }
  return errors
}

/** One completion rule for pipe/file execution and failure-injection tests. */
function completion(status,output,errorCode,logError) {
  const match=String(output||'').match(/AQUAFLOW_SUITE_OK\s+(\d+)/)
  const value=match?Number(match[1]):0, count=Number.isSafeInteger(value)&&value>0?value:0
  return {count,passed:status===0&&count>0&&!errorCode&&!logError}
}
module.exports={registryProblems,completion}
