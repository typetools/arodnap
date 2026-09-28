// doctor, analyze and infer each write their output; skip leaves nothing behind.
import groovy.json.JsonSlurper

def json = { path -> new JsonSlurper().parse(new File(basedir, path)) }

assert json('target/arodnap/doctor.json').success

def analyze = json('target/analyze/report.json')
assert analyze.success : analyze.error
assert analyze.run_metadata.command == 'analyze'
assert analyze.analysis_runs[0].warning_count > 0
assert new File(analyze.analysis_runs[0].wpi_log_path).text.startsWith('SKIPPED')

def infer = json('target/infer/report.json')
assert infer.success : infer.error
assert infer.run_metadata.command == 'infer'
assert !new File(infer.analysis_runs[0].wpi_log_path).text.startsWith('SKIPPED')

assert !new File(basedir, 'target/skipped').exists()
assert new File(basedir, 'build.log').text.count('Skipping Arodnap (arodnap.skip).') == 2
