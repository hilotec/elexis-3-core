#!/usr/bin/env ruby
# coding: utf-8
# License: Eclipse Public License 1.0
# Copyright Niklaus Giger, 2013, niklaus.giger@member.fsf.org

# Allows us to run the Elexis Jubula GUI-Tests as Jenkis CI-job

# As of May 2014 this becomes a bit more complicated as we need to the following stuff
# We first must run a special test which does the following stuff
#  1) Use the local P2-repository (elexis-3-core) to check upgrade to myself
#  2) Install all SW from the public elexis-3-base P2-site to get the Artikelstamm
#  3) Reboot
#  4) Patch Jubula testcases to add names for all views/perspectives from elexis-3-core and base
#  5) Run the new FULLTEST which needs Tarmed and Artikelstamm to be able to create invoices

require "#{File.dirname(__FILE__)}/jubulaoptions"
require "#{File.dirname(__FILE__)}/jubularun"
$stdout.sync=true

opts = JubulaOptions::parseArgs
opts.parse!(ARGV)
JubulaOptions::dryRun == true ? DryRun = true : DryRun = false
variant = ENV['VARIANT']
dbUserPw = '-Dch.elexis.dbUser=elexis -Dch.elexis.dbPw=elexisTest'
dbRunMode = '-Delexis-run-mode=RunFromScratch -Dch.elexis.username=007 -Dch.elexis.password=topsecret'
@hasDemoDb = false
case variant
  when /postgres/i
    dbOpts = ' -Dch.elexis.dbFlavor=postgresql  -Dch.elexis.dbSpec=jdbc:postgresql://localhost/elexis ' + dbUserPw
  when /mysql/i
    dbOpts = '-Dch.elexis.dbFlavor=mysql        -Dch.elexis.dbSpec=jdbc:mysql://localhost/elexis_empty ' + dbUserPw
  when /demoDb/i
    system("wget --quiet --timestamping http://download.elexis.info/demoDB/demoDB_2_1_7_mit_Administrator.zip")
    system("unzip -o demoDB_2_1_7_mit_Administrator.zip")
    FileUtils.mv('demoDB', File.join(Dir.home, 'elexis'), :verbose => true)
    dbOpts = '-DdbOpts=h2'
    dbRunMode = '-Dch.elexis.username=test -Dch.elexis.password=test'
    @hasDemoDb = true
  else
    dbOpts = '-DdbOpts=h2'
end

# browser: workaround see https://bugs.eclipse.org/bugs/show_bug.cgi?id=404776, which was not backported to 3.8.2, only in 4.3.2
# and for p2.unsignedPolicy https://bugs.eclipse.org/bugs/show_bug.cgi?id=235526. Disables the security popup while running SW-Upgrade
workArounds = '-Declipse.p2.unsignedPolicy=allow -Dorg.eclipse.swt.browser.DefaultType=mozilla '


jubula = JubulaRun.new(:portNumber => 60000 + (Process.pid % 1000),
                       :vmargs => "#{dbOpts} #{dbRunMode} #{workArounds} ",
                       :autid => 'elexis',
                       )
# For unknown reasons (which took me a few hours to code around) I decided
# that is is not my aim to use a MySQL database to store the Jubula testcases
# Instead we also start from a fresh, empty workspace and an empty embedded H2 db
# Costs me a good minute

wsDir = "#{jubula.workspace}/test-ws"
FileUtils.rm_rf(wsDir, :verbose => true, :noop => DryRun)

def save_images(destination)
  Dir.glob("**/*shot*/*.png").each{
    |x|
        next if /images/.match(x)
        next if /plugins/.match(x)
        next if /#{File.basename(destination)}/.match(x)
        FileUtils.cp(x, "#{destination}", :verbose => true, :noop => DryRun)
  }
end

def run_upgrade_local_core_and_remote_base(jubula, label)
  res = true
  ENV['TEST_UDV_SW_MUST_UPGRADE'] = 'true' # we want installing all SW-features to succeed
  jubula.clean_settings
  jubula.cleanDemoDb unless @hasDemoDb
  jubula.genWrapper
  jubula.prepareRcpSupport
#  jubula.patchXML # not needed for SW-Upgrade
  jubula.useH2(Dir.pwd)
  jubula.rmTestcases  # only if using h2
  jubula.loadTestcases    # only if using h2
  res = jubula.runOneTestcase(label, 15)
  jubula.saveImages
  jubula.checkOutcome(res, label)
end

def run_fulltest(jubula, label)
  jubula.useH2(Dir.pwd)
  jubula.patchXML
  jubula.rmTestcases  # only if using h2
  jubula.loadTestcases    # only if using h2
  res = jubula.runOneTestcase(label, 15)
  jubula.saveImages
  jubula.checkOutcome(res, label)
  # TODO: Check for other *.jubula*.xml files to execute as TestCases, eg. Omnivore, KG-Iatrix
end

FileUtils.makedirs(jubula.dataDir, :verbose => true)
FileUtils.cp('artikelstamm_first_v2.xml', jubula.testResults, :verbose => true)
run_upgrade_local_core_and_remote_base(jubula, 'TST_UPGRADE')
run_fulltest(jubula, 'FULLTEST')

