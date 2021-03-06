/*
 * Copyright (c) 2013-2017, Centre for Genomic Regulation (CRG).
 * Copyright (c) 2013-2017, Paolo Di Tommaso and the respective authors.
 *
 *   This file is part of 'Nextflow'.
 *
 *   Nextflow is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   Nextflow is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with Nextflow.  If not, see <http://www.gnu.org/licenses/>.
 */

package nextflow.processor
import java.nio.file.Paths

import nextflow.exception.FailedGuardException
import nextflow.script.BaseScript
import nextflow.script.TaskClosure
import nextflow.util.Duration
import nextflow.util.MemoryUnit
import spock.lang.Specification
/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class TaskConfigTest extends Specification {


    def testShell() {

        when:
        def config = new TaskConfig().setContext(my_shell: 'hello')
        config.shell = value
        then:
        config.shell == expected
        config.getShell() == expected

        where:
        expected             | value
        ['/bin/bash', '-ue'] | null
        ['/bin/bash', '-ue'] | []
        ['/bin/bash', '-ue'] | ''
        ['bash']             | 'bash'
        ['bash']             | ['bash']
        ['bash', '-e']       | ['bash', '-e']
        ['zsh', '-x']        | ['zsh', '-x']
        ['hello']            | { "$my_shell" }
    }

    def testErrorStrategy() {

        when:
        def config = new TaskConfig(map)

        then:
        config.errorStrategy == strategy
        config.getErrorStrategy() == strategy

        where:
        strategy                    | map
        ErrorStrategy.TERMINATE     | [:]
        ErrorStrategy.TERMINATE     | [errorStrategy: 'terminate']
        ErrorStrategy.TERMINATE     | [errorStrategy: 'TERMINATE']
        ErrorStrategy.IGNORE        | [errorStrategy: 'ignore']
        ErrorStrategy.IGNORE        | [errorStrategy: 'Ignore']
        ErrorStrategy.RETRY         | [errorStrategy: 'retry']
        ErrorStrategy.RETRY         | [errorStrategy: 'Retry']

    }

    def testErrorStrategy2() {

        when:
        def config = new TaskConfig()
        config.context = [x:1]
        config.errorStrategy = value
        then:
        config.errorStrategy == expect
        config.getErrorStrategy() == expect

        where:
        expect                      | value
        ErrorStrategy.TERMINATE     | null
        ErrorStrategy.TERMINATE     | 'terminate'
        ErrorStrategy.TERMINATE     | 'TERMINATE'
        ErrorStrategy.IGNORE        | 'ignore'
        ErrorStrategy.IGNORE        | 'Ignore'
        ErrorStrategy.RETRY         | 'retry'
        ErrorStrategy.RETRY         | 'Retry'
        ErrorStrategy.RETRY         | { x == 1 ? 'retry' : 'ignore' }
        ErrorStrategy.FINISH        | 'finish'

    }

    def testModules() {

        def config
        def local

        when:
        config = new ProcessConfig([:])
        config.module 't_coffee/10'
        config.module( [ 'blast/2.2.1', 'clustalw/2'] )
        local = config.createTaskConfig()

        then:
        local.module == ['t_coffee/10','blast/2.2.1', 'clustalw/2']
        local.getModule() == ['t_coffee/10','blast/2.2.1', 'clustalw/2']

        when:
        config = new ProcessConfig([:])
        config.module 'a/1'
        config.module 'b/2:c/3'
        local = config.createTaskConfig()

        then:
        local.module == ['a/1','b/2','c/3']

        when:
        config = new ProcessConfig([:])
        config.module { 'a/1' }
        config.module { 'b/2:c/3' }
        config.module 'd/4'
        local = config.createTaskConfig()
        local.setContext([:])
        then:
        local.module == ['a/1','b/2','c/3', 'd/4']


        when:
        config = new ProcessConfig([:])
        config.module = 'b/2:c/3'
        local = config.createTaskConfig()

        then:
        local.module == ['b/2','c/3']
        local.getModule() == ['b/2','c/3']


    }

    def testMaxRetries() {

        when:
        def config = new TaskConfig()
        config.maxRetries = value
        then:
        config.maxRetries == expected
        config.getMaxRetries() == expected

        where:
        value   | expected
        null    | 0
        0       | 0
        1       | 1
        '3'     | 3
        10      | 10

    }

    def testMaxRetriesDefault() {
        TaskConfig config

        when:
        config = new TaskConfig()
        then:
        config.maxRetries == 0
        config.getMaxRetries() == 0
        config.getErrorStrategy() == ErrorStrategy.TERMINATE

        when:
        config = new TaskConfig()
        config.errorStrategy = 'retry'
        then:
        config.maxRetries == 1
        config.getMaxRetries() == 1
        config.errorStrategy == ErrorStrategy.RETRY
        config.getErrorStrategy() == ErrorStrategy.RETRY

        when:
        config = new TaskConfig()
        config.maxRetries = 3
        config.errorStrategy = 'retry'
        then:
        config.maxRetries == 3
        config.getMaxRetries() == 3
        config.errorStrategy == ErrorStrategy.RETRY
        config.getErrorStrategy() == ErrorStrategy.RETRY
    }

    def testMaxErrors() {

        when:
        def config = new TaskConfig()
        config.maxErrors = value
        then:
        config.maxErrors == expected
        config.getMaxErrors() == expected

        where:
        value   | expected
        null    | 0
        0       | 0
        1       | 1
        '3'     | 3
        10      | 10

    }


    def testGetTime() {

        when:
        def config = new TaskConfig().setContext(ten: 10)
        config.time = value

        then:
        config.time == expected
        config.getTime() == expected

        where:
        expected            || value
        null                || null
        new Duration('1s')  || 1000
        new Duration('2h')  || '2h'
        new Duration('10h') || { "$ten hours" }

    }

    def testGetMemory() {

        when:
        def config = new TaskConfig().setContext(ten: 10)
        config.memory = value

        then:
        config.memory == expected
        config.getMemory() == expected

        where:
        expected                || value
        null                    || null
        new MemoryUnit('1K')    || 1024
        new MemoryUnit('2M')    || '2M'
        new MemoryUnit('10G')   || { "$ten G" }

    }

    def testGetDisk() {

        when:
        def config = new TaskConfig().setContext(x: 20)
        config.disk = value

        then:
        config.disk == expected
        config.getDisk() == expected

        where:
        expected                || value
        null                    || null
        new MemoryUnit('1M')    || 1024 * 1024
        new MemoryUnit('5M')    || '5M'
        new MemoryUnit('20G')   || { "$x G" }
        new MemoryUnit('30G')   || MemoryUnit.of('30G')

    }

    def testGetCpus() {

        when:
        def config = new TaskConfig().setContext(ten: 10)
        config.cpus = value

        then:
        config.cpus == expected
        config.getCpus() == expected

        where:
        expected                || value
        1                       || null
        1                       || 1
        8                       || 8
        10                      || { ten ?: 0  }

    }

    def testGetStore() {

        when:
        def config = new TaskConfig()
        config.storeDir = value

        then:
        config.storeDir == expected
        config.getStoreDir() == expected

        where:
        expected                            || value
        null                                || null
        Paths.get('/data/path/')            || '/data/path'
        Paths.get('hello').toAbsolutePath() || 'hello'

    }


    def testGetClusterOptionsAsList() {

        when:
        def config = new TaskConfig()
        config.clusterOptions = value

        then:
        config.getClusterOptionsAsList() == expected

        where:
        expected                            || value
        Collections.emptyList()             || null
        ['-queue','alpha']                  || ['-queue','alpha']
        ['-queue','alpha']                  || '-queue alpha'
        ['-queue','alpha and beta']         || "-queue 'alpha and beta"
    }

    def testIsDynamic() {

        given:
        def config = new TaskConfig()

        when:
        config.alpha = 1
        config.delta = 2
        then:
        !config.isDynamic()

        when:
        config.delta = { 'this' }
        then:
        config.isDynamic()

        when:
        config.foo = { 'this' }
        config.bar = { 'this' }
        then:
        config.isDynamic()

        when:
        config = new TaskConfig( alpha:1, beta: { 'hello' } )
        then:
        config.isDynamic()

        when:
        config = new TaskConfig( alpha:1, beta: "${->foo}" )
        then:
        config.isDynamic()


    }

    def 'should return a new value when changing context' () {

        given:
        def config = new TaskConfig()
        config.alpha = 'Simple string'
        config.beta = { 'Static' }
        config.delta = { foo }
        config.gamma = "${-> bar }"

        when:
        config.setContext( foo: 'Hello', bar: 'World' )
        then:
        config.alpha == 'Simple string'
        config.beta == 'Static'
        config.delta == 'Hello'
        config.gamma == 'World'

        when:
        config.setContext( foo: 'Hola', bar: 'Mundo' )
        then:
        config.alpha == 'Simple string'
        config.beta == 'Static'
        config.delta == 'Hola'
        config.gamma == 'Mundo'
    }

    def 'should return the guard condition' () {

        given:
        def config = new TaskConfig()
        def closure = new TaskClosure({ x == 'Hello' && count==1 }, '{closure source code}')
        config.put('when', closure)

        when:
        config.getGuard('when')
        then:
        FailedGuardException ex = thrown()
        ex.source == '{closure source code}'

        when:
        config.context = [x: 'Hello', count: 1]
        then:
        config.getGuard('when')

        when:
        config.context = [x: 'Hello', count: 3]
        then:
        !config.getGuard('when')

    }

    def 'should create ext config properties' () {

        given:
        def config = new TaskConfig()
        config.ext.alpha = 'AAAA'
        config.ext.delta = { foo }
        config.ext.omega = "${-> bar}"

        when:
        config.setContext( foo: 'DDDD', bar: 'OOOO' )
        then:
        config.isDynamic()
        config.ext.alpha == 'AAAA'
        config.ext.delta == 'DDDD'
        config.ext.omega == 'OOOO'

        when:
        config.setContext( foo: 'dddd', bar: 'oooo' )
        then:
        config.ext.alpha == 'AAAA'
        config.ext.delta == 'dddd'
        config.ext.omega == 'oooo'

    }


    def 'should create publishDir object' () {

        setup:
        def script = Mock(BaseScript)
        def process = new ProcessConfig(script)
        PublishDir publish

        when:
        process.publishDir '/data'
        publish = process.createTaskConfig().getPublishDir()
        then:
        publish.path == Paths.get('/data').complete()
        publish.pattern == null
        publish.overwrite == null
        publish.mode == null


        when:
        process.publishDir '/data', overwrite: false, mode: 'copy', pattern: '*.txt'
        publish = process.createTaskConfig().getPublishDir()
        then:
        publish.path == Paths.get('/data').complete()
        publish.pattern == '*.txt'
        publish.overwrite == false
        publish.mode == PublishDir.Mode.COPY

    }

    def 'should create publishDir with local variables' () {

        given:
        TaskConfig config

        // It is defined using a local variable e.g.
        // publishDir "$x"
        when:
        config = new TaskConfig()
        config.publishDir = "${-> foo }/${-> bar }"
        config.setContext( foo: 'hello', bar: 'world' )
        then:
        config.getPublishDir() == PublishDir.create('hello/world')

        // It is defined using named parameters
        // publishDir path: "$x", mode: "$y"
        when:
        config = new TaskConfig()
        config.publishDir = [path: "${-> foo }/${-> bar }", mode: "${-> x }"]
        config.setContext( foo: 'world', bar: 'hello', x: 'copy' )
        then:
        config.getPublishDir() == PublishDir.create(path: 'world/hello', mode: 'copy')

        // It is defined using both named parameters and local vars
        // publishDir "/data/$output", mode: "$x"
        when:
        config = new TaskConfig()
        config.publishDir = [[ mode: "${-> x }"], "${-> foo }/${-> bar }"]
        config.setContext( foo: 'world', bar: 'hello', x: 'copy' )
        then:
        config.getPublishDir() == PublishDir.create(path: 'world/hello', mode: 'copy')


        // It is defined using both named parameters and local vars
        // publishDir { "/data/$output" }, mode: "$x"
        when:
        config = new TaskConfig()
        config.publishDir = [[ mode: "${-> x }"], { "$foo/$bar" }]
        config.setContext( foo: 'hello', bar: 'world', x: 'copy' )
        then:
        config.getPublishDir() == PublishDir.create(path: 'hello/world', mode: 'copy')


    }

    def 'should invoke dynamic cpus property only when cloning the config object' () {

        given:
        def config = new TaskConfig()

        when:
        int count = 0
        config.cpus = { ++count }
        then:
        config.getCpus() == 1
        config.getCpus() == 1

        when:
        config = config.clone()
        then:
        config.getCpus() == 2
        config.getCpus() == 2

        when:
        config = config.clone()
        then:
        config.getCpus() == 3
        config.getCpus() == 3
    }

}
