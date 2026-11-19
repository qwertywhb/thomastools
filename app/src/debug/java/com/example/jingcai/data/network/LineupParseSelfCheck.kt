package com.example.jingcai.data.network

import com.example.jingcai.data.model.EventKind
import com.example.jingcai.data.model.LineupMark

/**
 * 首发/替补解析自检（纯 JVM + jsoup，不用真机）。
 *
 * 背景：长按比分弹层要在「技术统计」下面加「首发阵容 / 替补」。数据来自详情页
 * `div#matchBox2`，结构层次**极易认错**，且错了不会抛异常、只会静默少人/多人：
 *  1. 首发在 `div.plays`，替补在 `div.backupPlay2`；旁边还有个 `div.backupPlay.hurtPlay`（伤停），
 *     用 `div.backupPlay` 当选择器会把伤停名单也算进替补；
 *  2. 首发球衣号在 `em.num`，替补球衣号在 `div.name i` —— 只在 `.num` 取，替补全没号；
 *  3. 标记（⚽/牌/换人）是球员块里的 `img[title]`，`title` 形如 `45+9' 红牌`；
 *     「两黄变红」不含「红牌」两字以外的线索，判断顺序错了会显示成黄牌；
 *  4. 阵型/教练在 `div.title > div.homeN|guestN`，是 `#matchBox2` 的**兄弟**节点，不是子节点。
 *
 * 断言里的结构与文字取自实测样本 `detail/3105636cn.htm`（布鲁明 vs 奥利恩特）与
 * `detail/2997481cn.htm`（克雷塔罗 vs 普埃布拉）。
 *
 * 跑法（见 docs）：
 * ```
 * java -cp "app/build/tmp/kotlin-classes/debug;<jsoup.jar>;<kotlin-stdlib.jar>" \
 *      com.example.jingcai.data.network.LineupParseSelfCheck
 * ```
 */
object LineupParseSelfCheck {

    private var pass = 0
    private var fail = 0

    @JvmStatic
    fun main(args: Array<String>) {
        println("==== 首发/替补解析自检 ====")

        val lu = TitanDetailParser.parseLineup(SAMPLE)
        check("能解析出阵容", lu != null, "got=null")
        if (lu == null) return finish()

        // ① 阵型 / 教练：来自兄弟节点 div.title，左右不能串
        check("主队阵型 4-2-3-1", lu.home.formation == "4-2-3-1", "got=${lu.home.formation}")
        check("客队阵型 4-4-1-1", lu.away.formation == "4-4-1-1", "got=${lu.away.formation}")
        check("主队教练", lu.home.coach == "毛里斯奧.索里亚", "got=${lu.home.coach}")
        check("客队教练", lu.away.coach == "大卫·冈萨雷斯", "got=${lu.away.coach}")

        // ② 首发：11 人，球衣号取 em.num
        check("主队首发 11 人", lu.home.starters.size == 11, "got=${lu.home.starters.size}")
        check("客队首发 11 人", lu.away.starters.size == 11, "got=${lu.away.starters.size}")
        val p1 = lu.home.starters.first()
        check("首发号码取 em.num（71）", p1.number == "71", "got='${p1.number}'")
        check("首发姓名（阿尔马达）", p1.name == "阿尔马达", "got='${p1.name}'")
        check("队长标记", lu.home.starters.any { it.captain }, "无人被标为队长")

        // ③ 替补：从 backupPlay2 取，号码取 div.name i
        check("主队替补 12 人", lu.home.subs.size == 12, "got=${lu.home.subs.size}")
        check("替补号码取 div.name i（28）", lu.home.subs.first().number == "28", "got='${lu.home.subs.first().number}'")
        check("替补姓名（阿尔瓦雷斯）", lu.home.subs.first().name == "阿尔瓦雷斯", "got='${lu.home.subs.first().name}'")

        // ④ 伤停区（div.backupPlay.hurtPlay）不能被当成替补：样本里只有 1 人
        check("伤停不计入替补", lu.home.subs.none { it.name == "伤停甲" }, "替补=${lu.home.subs.map { it.name }}")

        // ⑤ 标记：红牌 / 黄牌 / 换入 / 换出 / 助攻；「两黄变红」不能被认成黄牌
        val red = lu.home.starters.first { it.name == "维拉" }.marks
        check("红牌标记", red.any { it.kind == EventKind.RED && it.minute == "45+9'" }, "got=$red")
        val yellow = lu.home.starters.first { it.name == "巴尔韦德" }.marks
        check("黄牌标记", yellow.any { it.kind == EventKind.YELLOW && it.minute == "66'" }, "got=$yellow")
        val secondYellow = lu.away.starters.first { it.name == "两黄甲" }.marks
        check(
            "两黄变红不认成黄牌",
            secondYellow.any { it.kind == EventKind.SECOND_YELLOW } && secondYellow.none { it.kind == EventKind.YELLOW },
            "got=$secondYellow"
        )
        val subOn = lu.home.subs.first { it.name == "阿尔瓦雷斯" }.marks
        check("换入标记", subOn.any { it.kind == LineupMark.SUB_ON && it.minute == "46'" }, "got=$subOn")
        val subOff = lu.away.starters.first { it.name == "埃斯科巴" }.marks
        check("换出标记", subOff.any { it.kind == LineupMark.SUB_OFF && it.minute == "58'" }, "got=$subOff")
        val goal = lu.away.starters.first { it.name == "贝拉斯科" }.marks
        check("入球标记", goal.any { it.kind == EventKind.GOAL && it.minute == "9'" }, "got=$goal")
        val assist = lu.away.starters.first { it.name == "贝拉斯科" }.marks
        check("助攻标记", assist.any { it.kind == LineupMark.ASSIST }, "got=$assist")

        // ⑥ 认不出的图标（扑出点球/最佳球员）不能被瞎猜成某类
        val unknown = lu.home.starters.first { it.name == "阿尔马达" }.marks
        check("未知图标丢弃", unknown.isEmpty(), "got=$unknown")

        // ⑦ 空壳行（div.play 里没有名字）要跳过，不能留空行
        check("空名行被跳过", lu.home.starters.none { it.name.isEmpty() }, "有空名行")

        // ⑧ 没有该板块 / 空 HTML ⇒ null（不是抛异常）
        check("无 matchBox2 ⇒ null", TitanDetailParser.parseLineup("<div>no lineup</div>") == null)
        check("空 HTML ⇒ null", TitanDetailParser.parseLineup("") == null)
        check("matchBox2 但无球员 ⇒ null", TitanDetailParser.parseLineup("<div id='matchBox2'></div>") == null)

        finish()
    }

    private val SAMPLE = """
        <div class="content">
          <div class="title">
            首发阵容
            <div class="homeN"><a href="#">布鲁明</a> 4-2-3-1<a class="coach" href="#" title="毛里斯奧.索里亚">(主教练: 毛里斯奧.索里亚)</a></div>
            <div class="guestN"><a class="coach" href="#" title="大卫·冈萨雷斯">(主教练: 大卫·冈萨雷斯)</a><a href="#">奥利恩特</a> 4-4-1-1</div>
          </div>
          <div id="matchBox2">
            <div class="plays">
              <div class="home">
                <div class="playBox">
                  <div class="play"><span><div class="headicon"><em class="num">71 </em></div><div class="name"><a>阿尔马达</a></div>
                    <ul><li>姓名：古斯塔沃·阿尔马达</li></ul></span><div id="playerTech_1"></div></div>
                  <div class="play"><div class="captain"></div><span><div class="headicon"><em class="num">6 </em></div><div class="name"><a>斯彭海</a></div></span><div id="playerTech_2"></div></div>
                </div>
                <div class="playBox">
                  <div class="play"><span><div class="headicon"><em class="num">22 </em></div><div class="name"><a>维拉</a></div></span><div id="playerTech_3"><img alt="45+9'" title="45+9' 红牌" src="/images/bf_img2/2.png"/></div></div>
                  <div class="play"><span><div class="headicon"><em class="num">3 </em></div><div class="name"><a>巴尔韦德</a></div></span><div id="playerTech_4"><img alt="66'" title="66' 黄牌" src="/images/bf_img2/3.png"/></div></div>
                </div>
                <div class="playBox">
                  <div class="play"><span><div class="headicon"><em class="num">18 </em></div><div class="name"><a>冈萨雷斯</a></div></span></div>
                  <div class="play"><span><div class="headicon"><em class="num">24 </em></div><div class="name"><a>贾斯蒂尼亚诺</a></div></span></div>
                </div>
                <div class="playBox">
                  <div class="play"><span><div class="headicon"><em class="num">9 </em></div><div class="name"><a>甲</a></div></span></div>
                  <div class="play"><div class="name"></div></div>
                </div>
                <div class="playBox">
                  <div class="play"><span><div class="headicon"><em class="num">10 </em></div><div class="name"><a>乙</a></div></span></div>
                  <div class="play"><span><div class="headicon"><em class="num">11 </em></div><div class="name"><a>丙</a></div></span></div>
                </div>
                <div class="playBox">
                  <div class="play"><span><div class="headicon"><em class="num">12 </em></div><div class="name"><a>丁</a></div></span></div>
                  <div class="play"><span><div class="headicon"><em class="num">13 </em></div><div class="name"><a>戊</a></div></span></div>
                </div>
              </div>
              <div class="guest">
                <div class="playBox">
                  <div class="play"><span><div class="headicon"><em class="num">9 </em></div><div class="name"><a>贝拉斯科</a></div></span>
                    <div id="playerTech_9"><img alt="9'" title="9' 入球" src="/images/bf_img2/1.png"/><img alt="36'" title="36' 助攻" src="/images/bf_img2/10.png"/></div></div>
                  <div class="play"><span><div class="headicon"><em class="num">29 </em></div><div class="name"><a>埃斯科巴</a></div></span>
                    <div id="playerTech_29"><img alt="58'" title="58' 换出" src="/images/bf_img2/4.png"/></div></div>
                </div>
                <div class="playBox">
                  <div class="play"><span><div class="headicon"><em class="num">5 </em></div><div class="name"><a>两黄甲</a></div></span>
                    <div id="playerTech_5"><img alt="70'" title="70' 两黄变红" src="/images/bf_img2/9.png"/></div></div>
                  <div class="play"><span><div class="headicon"><em class="num">6 </em></div><div class="name"><a>客乙</a></div></span></div>
                </div>
                <div class="playBox">
                  <div class="play"><span><div class="headicon"><em class="num">7 </em></div><div class="name"><a>客丙</a></div></span></div>
                  <div class="play"><span><div class="headicon"><em class="num">8 </em></div><div class="name"><a>客丁</a></div></span></div>
                </div>
                <div class="playBox">
                  <div class="play"><span><div class="headicon"><em class="num">11 </em></div><div class="name"><a>客戊</a></div></span></div>
                  <div class="play"><span><div class="headicon"><em class="num">12 </em></div><div class="name"><a>客己</a></div></span></div>
                </div>
                <div class="playBox">
                  <div class="play"><span><div class="headicon"><em class="num">13 </em></div><div class="name"><a>客庚</a></div></span></div>
                  <div class="play"><span><div class="headicon"><em class="num">14 </em></div><div class="name"><a>客辛</a></div></span></div>
                </div>
                <div class="playBox">
                  <div class="play"><span><div class="headicon"><em class="num">15 </em></div><div class="name"><a>客壬</a></div></span></div>
                </div>
              </div>
            </div>
            <div class="backupPlay backupPlay2">
              <div class="home">
                <div class="play"><span><div class="name"><i>28 </i><a>阿尔瓦雷斯</a></div>
                  <div class="eventicon" id="playerTech_28"><img alt="46'" title="46' 换入" src="/images/bf_img2/5.png"/></div></span></div>
                <div class="play"><span><div class="name"><i>16 </i><a>D.贝加莱诺</a></div><div class="eventicon"></div></span></div>
                <div class="play"><span><div class="name"><i>2 </i><a>卡布拉尔</a></div><div class="eventicon"></div></span></div>
                <div class="play"><span><div class="name"><i>3 </i><a>替补丁</a></div></span></div>
                <div class="play"><span><div class="name"><i>4 </i><a>替补戊</a></div></span></div>
                <div class="play"><span><div class="name"><i>5 </i><a>替补己</a></div></span></div>
                <div class="play"><span><div class="name"><i>6 </i><a>替补庚</a></div></span></div>
                <div class="play"><span><div class="name"><i>7 </i><a>替补辛</a></div></span></div>
                <div class="play"><span><div class="name"><i>8 </i><a>替补壬</a></div></span></div>
                <div class="play"><span><div class="name"><i>9 </i><a>替补癸</a></div></span></div>
                <div class="play"><span><div class="name"><i>10 </i><a>替补子</a></div></span></div>
                <div class="play"><span><div class="name"><i>11 </i><a>替补丑</a></div></span></div>
              </div>
              <div class="bu_txt"><span class="center"><img class="subicon" src="/images/statistics/substitute.png"/> 替补 </span></div>
              <div class="guest">
                <div class="play"><span><div class="name"><i>21 </i><a>阿庞特</a></div><div class="eventicon"></div></span></div>
                <div class="play"><span><div class="name"><i>13 </i><a>伯纳尔</a></div><div class="eventicon"></div></span></div>
                <div class="play"><span><div class="name"><i>17 </i><a>加林多</a></div><div class="eventicon"></div></span></div>
                <div class="play"><span><div class="name"><i>77 </i><a>赫雷拉</a></div>
                  <div class="eventicon" id="playerTech_77"><img alt="71'" title="71' 黄牌" src="/images/bf_img2/3.png"/><img alt="69'" title="69' 换入" src="/images/bf_img2/4.png"/></div></span></div>
                <div class="play"><span><div class="name"><i>19 </i><a>佩雷斯</a></div></span></div>
                <div class="play"><span><div class="name"><i>6 </i><a>迪吉托</a></div></span></div>
                <div class="play"><span><div class="name"><i>79 </i><a>洛尔丹</a></div></span></div>
                <div class="play"><span><div class="name"><i>9 </i><a>罗斯科夫</a></div></span></div>
                <div class="play"><span><div class="name"><i>8 </i><a>西莱斯</a></div></span></div>
                <div class="play"><span><div class="name"><i>10 </i><a>客替子</a></div></span></div>
                <div class="play"><span><div class="name"><i>11 </i><a>客替丑</a></div></span></div>
                <div class="play"><span><div class="name"><i>12 </i><a>客替寅</a></div></span></div>
              </div>
              <div class="clean"></div>
            </div>
            <div class="backupPlay hurtPlay">
              <div class="home">
                <div class="play"><span><div class="name"><i>99 </i><a>伤停甲</a></div></span></div>
              </div>
              <div class="bu_txt"><span class="center">伤停</span></div>
              <div class="guest"></div>
            </div>
          </div>
        </div>
    """.trimIndent()

    private fun check(name: String, ok: Boolean, detail: String = "") {
        if (ok) {
            pass++
            println("PASS  $name")
        } else {
            fail++
            println("FAIL  $name   $detail")
        }
    }

    private fun finish() {
        println("---- 通过 $pass / 失败 $fail ----")
        if (fail > 0) {
            println("LINEUP_PARSE_SELFCHECK: FAIL")
            System.exit(1)
        }
        println("LINEUP_PARSE_SELFCHECK: ALL PASS")
    }
}
