// A leak in each module of a two-module reactor, where app uses core: both are repaired in one patch.
import groovy.json.JsonSlurper

def out = new File(basedir, 'target/arodnap')
def report = new JsonSlurper().parse(new File(out, 'report.json'))
assert report.success : report.error
assert report.leaks.summary.fixed == 2
assert report.leaks.summary.remaining == 0

def core = 'core/src/main/java/demo/core/FirstByte.java'
def app = 'app/src/main/java/demo/app/Report.java'
def manifest = new JsonSlurper().parse(new File(out, 'patches/manifest.json'))
assert manifest.patches.size() == 1
assert manifest.patches[0].changed_files.sort() == [app, core]

// Applied by the apply goal, and the project still compiles (the third build).
[core, app].each {
    assert new File(basedir, it).text.contains('try (FileInputStream in = new FileInputStream(path))')
}
assert new File(basedir, 'app/target/classes/demo/app/Report.class').isFile()
