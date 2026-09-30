param([switch]$All)
$ErrorActionPreference = 'Stop'
$worker = Get-Content -Encoding UTF8 'C:/Users/DELL/.gradle/.tmp/gradle-worker-classpath16396057681371034652txt'
$dependencies = $worker[1].Trim('"').Replace('\\','\').Split(';') |
    Where-Object { $_ -match '^C:' -and $_ -match 'junit-4|hamcrest|kotlin-stdlib|firebase-common|firebase-firestore|play-services-basement|ui-graphics|ui-unit|ui-geometry|runtime-android|ui-util' }
$classpath = (@('app/build/tmp/kotlin-classes/debugUnitTest', 'app/build/intermediates/runtime_app_classes_jar/debug/bundleDebugClassesToRuntimeJar/classes.jar', 'C:/Users/DELL/AppData/Local/Android/Sdk/platforms/android-35/android.jar') + $dependencies) -join ';'
$classes = @('vn.chamcong.iot.domain.PresenceRulesTest', 'vn.chamcong.iot.domain.ReportRulesTest', 'vn.chamcong.iot.model.PayrollRulesTest', 'vn.chamcong.iot.domain.EmployeeRulesTest')
if ($All) {
    $classes = Get-ChildItem app/build/tmp/kotlin-classes/debugUnitTest -Recurse -Filter '*Test.class' |
        ForEach-Object { $_.FullName.Substring((Join-Path (Get-Location) 'app/build/tmp/kotlin-classes/debugUnitTest').Length + 1).Replace('\','.').Replace('.class','') }
}
& java -cp $classpath org.junit.runner.JUnitCore @classes
exit $LASTEXITCODE
