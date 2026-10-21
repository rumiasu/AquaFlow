param(
 [Parameter(Mandatory=$true)][string]$Cli,
 [Parameter(Mandatory=$true)][string]$VisualProjects,
 [Parameter(Mandatory=$true)][string]$FixtureManifest,
 [Parameter(Mandatory=$true)][string]$Output,
 [string]$Plan=(Join-Path $PSScriptRoot 'representative-pages.json')
)
$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$OutputEncoding=[Text.UTF8Encoding]::new($false)
$Cli=(Resolve-Path -LiteralPath $Cli).Path
$VisualProjects=(Resolve-Path -LiteralPath $VisualProjects).Path
$manifest=Get-Content -LiteralPath $FixtureManifest -Raw -Encoding utf8 | ConvertFrom-Json
if($manifest.type -ne 'offline_native_visual_samples' -or $manifest.businessValidation -ne $false -or $manifest.sourceCommit -notmatch '^[a-f0-9]{40}$'){throw 'Expected an offline visual fixture manifest'}
$items=Get-Content -LiteralPath $Plan -Raw -Encoding utf8 | ConvertFrom-Json
foreach($item in $items){
 if($item.id -notmatch '^[a-zA-Z0-9_-]+$' -or $item.side -notin @('miniapp-user','miniapp-delivery')){throw 'Invalid capture identifier or side'}
 if(!@($manifest.pages | Where-Object {$_.side -eq $item.side -and $_.route -eq $item.route}).Count){throw 'Page absent from offline fixture manifest'}
 $app=Get-Content -LiteralPath (Join-Path $VisualProjects ($item.side+'/app.js')) -Raw -Encoding utf8
 if($app -notmatch 'visualSample:true' -or $app -notmatch 'offline visual fixture: blocked'){throw 'Expected a prepared isolated visual project; do not point this script at product source'}
}
$out=[IO.Path]::GetFullPath($Output)
New-Item -ItemType Directory -Force $out,(Join-Path $out 'originals'),(Join-Path $out 'receipts') | Out-Null
$resultsFile=Join-Path $out 'captures.json'
$results=@();if(Test-Path $resultsFile){$results=@(Get-Content $resultsFile -Raw -Encoding utf8 | ConvertFrom-Json)}
$lastSuccess=[DateTime]::UtcNow
function Invoke-VisualTool([string]$tool,[string]$project,[string[]]$extra,[string]$label){
 $a=@('-c','Codex',$tool,'--project',$project)+$extra
 for($attempt=1;$attempt -le 3;$attempt++){
  $raw=(& $cli @a | Out-String)
  $raw | Set-Content -LiteralPath (Join-Path $out ('receipts/'+$label+'-attempt'+$attempt+'.txt')) -Encoding utf8
  $start=$raw.IndexOf('{');if($start -lt 0){throw ('No JSON: '+$raw)}
  $parsed=$raw.Substring($start) | ConvertFrom-Json
  if($parsed.ok -and $parsed.result.success){$script:lastSuccess=[DateTime]::UtcNow;return $parsed.result}
  if($raw -match 'Client network socket disconnected before secure TLS connection was established|read ECONNRESET' -and $attempt -lt 3){Start-Sleep -Seconds 3;continue}
  throw ($raw)
 }
}
foreach($item in $items){
 if(@($results | Where-Object {$_.id -eq $item.id -and $_.status -eq 'saved'}).Count){continue}
 $project=Join-Path $VisualProjects $item.side
 $record=[ordered]@{id=$item.id;side=$item.side;route=$item.route;state=$item.state;variant=$item.variant;sourceCommit=$manifest.sourceCommit;kind='offline native visual sample';businessValidation=$false;startedAtUtc=[DateTime]::UtcNow.ToString('o');status='started'}
 if($item.replacementFor){$record.replacementFor=$item.replacementFor}
 try{
  if($item.route -notmatch '^[a-zA-Z0-9/_-]+$' -or ($item.variant -and $item.variant -notmatch '^[a-zA-Z0-9_-]+$')){throw 'Invalid visual route'}
  $url='/'+$item.route;if($item.variant){$url+='?visual='+$item.variant}
  try{
   $opened=Invoke-VisualTool 'automation_navigate' $project @('--action','reLaunch','--url',$url) ($item.id+'-open')
  }catch{
   if($_.Exception.Message -notmatch 'timeout waiting for automator response'){throw}
   $record.navigationOutcome='timeout; actual page checked before any screenshot'
  }
  $actual=Invoke-VisualTool 'automation_runtime_info' $project @('--action','currentPage') ($item.id+'-page')
  $record.actualPage=$actual.currentPage.path
  $record.fixtureSha256=(Get-FileHash -LiteralPath (Join-Path $project ($item.route+'.js')) -Algorithm SHA256).Hash.ToLower()
  if($record.actualPage -ne $item.route){throw ('Actual route mismatch: '+$record.actualPage)}
  $file=Join-Path $out ('originals/'+$item.id+'__VISUAL.png')
  if(Test-Path $file){throw 'Existing image without confirmed receipt; inspect before recapturing'}
  $shot=Invoke-VisualTool 'simulator_screenshot' $project @('--path',$file,'--optimize','false','--wait','1') ($item.id+'-screenshot')
  if(!(Test-Path $file)){throw 'Screenshot path returned but file missing'}
  $record.status='saved';$record.file='originals/'+$item.id+'__VISUAL.png';$record.sha256=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLower();$record.width=$shot.imageWidth;$record.height=$shot.imageHeight
 }catch{$record.status='failed';$record.error=$_.Exception.Message}
 $record.finishedAtUtc=[DateTime]::UtcNow.ToString('o')
 $results+=,[PSCustomObject]$record
 ConvertTo-Json -InputObject @($results) -Depth 12 | Set-Content -LiteralPath $resultsFile -Encoding utf8
 Write-Output ($item.id+' '+$record.status+' '+$record.actualPage)
 if($record.status -eq 'failed' -and $record.error -match 'User denied|pending|AUTH_|token_required'){break}
 if(([DateTime]::UtcNow-$lastSuccess).TotalSeconds -ge 120){Write-Output 'No successful tool response for two minutes; stopped safely';break}
}
