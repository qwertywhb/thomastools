/**
 * 版本号： {$smarty.const.JSLocalMatchPrivate}
 */
$().ready(function(){
	//获取当前页面dom对象
	var homecompobj=$(".homecomp");
	var awaycompobj=$(".awaycomp");
	var vscompobj=$(".vscomp");

	var hometrobj=homecompobj.find("tr");
	var awaytrobj=awaycompobj.find("tr");
	var vstrobj=vscompobj.find("tr");
	//td 列index
	var teamname=0;
	var homeindex=2;
	var awayindex=4;
	var recordindex=3;
	
	//存储是否显示本场比赛
	if(GetCookie('historyShowThisMatch') == 1){
		$('#jsSetVisible').attr('checked',true);
		$('.jsThisMatch').show();	
	}else{
		$('#jsSetVisible').attr('checked',false);
		$('.jsThisMatch').hide();
	}
	$('#jsSetVisible').click(function(){
		if(this.checked){
			SetCookie('historyShowThisMatch', 1, 100*24*60*60,'/');
			$('.jsThisMatch').show();
			google_p([action, '选择包含本场']);
		}else{
			SetCookie('historyShowThisMatch', 0, 100*24*60*60,'/');
			$('.jsThisMatch').hide();
			google_p([action, '取消包含本场']);
		}
	})

	//计算当前战绩
	function gethomerecord(team,returnid,trobj){
		//初始化战绩
		var victory=0;
		var failure=0;
		var draw=0;
		var homevictory=0;
		var homefailure=0;
		var homedraw=0;
		var totalHomeScore=0, totalAwayScore=0;

		for(var i=3,ilen=trobj.length;i<ilen;i++){
			if($(trobj[i]).css("display")=="none")continue;
			//获取比分信息
						var datasarr=$(trobj[i]).attr("attr").split(",");
						var recordval=datasarr[1];
			//var recordobj=$(trobj[i]).find("td").eq(recordindex);
			//var recordval=recordobj.attr("attr");
						if(recordval=="-")continue;
			var recordarr=recordval.split("-");
			var homescore=Number(recordarr[0]);
			var awayscore=Number(recordarr[1]);
			//获取队伍信息
			var homeid=$(trobj[i]).find("td").eq(homeindex).attr("attr");
			var awayid=$(trobj[i]).find("td").eq(awayindex).attr("attr");
			//计算战绩
			if(returnid==homeid){
				var checkscore=homescore-awayscore;
				if(checkscore>0){
					homevictory++;
				}else if(checkscore<0){
					homefailure++;
				}else if(checkscore==0){
					homedraw++
				}
				totalHomeScore += homescore;
				totalAwayScore += awayscore;
			}else if(returnid==awayid){
				var checkscore=awayscore-homescore;
				totalHomeScore += awayscore;
				totalAwayScore += homescore;
			}
			if(checkscore>0){
				victory++;
			}else if(checkscore<0){
				failure++;
			}else if(checkscore==0){
				draw++
			}
		}
		if(team!="away"){
			var gethtml=victory+"胜"+draw+"平"+failure+"负，主场"+homevictory+"胜"+homedraw+"平"+homefailure+"负，进"+totalHomeScore+'球失'+totalAwayScore+'球';
		}else{
			var gethtml=victory+"胜"+draw+"平"+failure+"负，客场"+(victory-homevictory)+"胜"+(draw-homedraw)+"平"+(failure-homefailure)+"负，进"+totalHomeScore+'球失'+totalAwayScore+'球';
		}
		return gethtml;
	}
	function getscorehtml(team,returnid,trobj){
		$("."+team+"score").html(gethomerecord(team,returnid,trobj));
	}
	//计算战绩
	getscorehtml("home",host_team_id,hometrobj);
	getscorehtml("away",guest_team_id,awaytrobj);
	getscorehtml("vs",host_team_id,vstrobj);
	
	//主队筛选
	$("#HomeLeagueFilter").change(function(){
		filter(hometrobj,"Home",host_team_id,$(this));
		getscorehtml("home",host_team_id,hometrobj);
	});
	$("#HomeFilter").change(function(){
		filter(hometrobj,"Home",host_team_id,$(this));
		getscorehtml("home",host_team_id,hometrobj);
	});
	$("#HomeCount").change(function(){
		filter(hometrobj,"Home",host_team_id,$(this));
		getscorehtml("home",host_team_id,hometrobj);
	});
	//客队筛选
	$("#AwayLeagueFilter").change(function(){
		filter(awaytrobj,"Away",guest_team_id,$(this));
		getscorehtml("away",guest_team_id,awaytrobj);
	});
	$("#AwayFilter").change(function(){
		filter(awaytrobj,"Away",guest_team_id,$(this));
		getscorehtml("away",guest_team_id,awaytrobj);
	});
	$("#AwayCount").change(function(){
		filter(awaytrobj,"Away",guest_team_id,$(this));
		getscorehtml("away",guest_team_id,awaytrobj);
	});
	//交锋筛选
	$("#VsLeagueFilter").change(function(){
		filter(vstrobj,"Vs",host_team_id,$(this));
		getscorehtml("vs",host_team_id,vstrobj);
	});
	$("#VsFilter").change(function(){
		filter(vstrobj,"Vs",host_team_id,$(this));
		getscorehtml("vs",host_team_id,vstrobj);
	});
	$("#VsCount").change(function(){
		filter(vstrobj,"Vs",host_team_id,$(this));
		getscorehtml("vs",host_team_id,vstrobj);
	});
	//筛选
	function filter(trobj,team,idval,curobj){
		var teamval=$("#"+team+"LeagueFilter").val();
		var haval=$("#"+team+"Filter").val();
		var conval=$("#"+team+"Count ").val();
		var checki=0;
		for(var i=3,ilen=trobj.length;i<ilen;i++){
			var teamtext=$(trobj[i]).find("td").eq(teamname).find('a').text().trim();
						var datasarr=$(trobj[i]).attr("attr").split(",");
						var homeid=datasarr[0];
						var awayid=datasarr[2];
			var isFriendly = $(trobj[i]).attr('data-lt');   //该场比赛时友谊赛 data-lt="friend"
			//var homeid=$(trobj[i]).find("td").eq(homeindex).attr("attr");
			//var awayid=$(trobj[i]).find("td").eq(awayindex).attr("attr");
			if(teamval == 'notFriendly' && isFriendly == 'friend'){
				$(trobj[i]).hide();
				continue;	
			}
			
			if(teamval!="all" && teamval!= 'notFriendly' && teamtext!=teamval ){
				$(trobj[i]).hide();
				continue;
			}
			if(haval!="all"&&haval=="Away"){
				if(awayid!=idval){
					$(trobj[i]).hide();
					continue;
				}
			}else if(haval!="all"&&haval=="Home"){
				if(homeid!=idval){
					$(trobj[i]).hide();
					continue;
				}
			}
			checki++;
			if(checki>Number(conval)){
				$(trobj[i]).hide();
				continue;
			}
			$(trobj[i]).show();
		}
		google_p([action, team + $('option:selected', curobj).text()]);
	}

	//切换赔率和盘口
	var tableObj={"homeqhplObj":homecompobj,"homeqhpkObj":homecompobj,"awayqhplObj":awaycompobj,"awayqhpkObj":awaycompobj,"vsqhplObj":vscompobj,"vsqhpkObj":vscompobj};
	$("#homeqhplObj,#homeqhpkObj,#awayqhplObj,#awayqhpkObj,#vsqhplObj,#vsqhpkObj").change(function(){
		var thisVal=this.value.split("_");
		var thisId=this.id;
		var thisTrObj=tableObj[thisId].find("tr");
		var matchId=[];
		for(var i=0,ilen=thisTrObj.length;i<ilen;i++){
				var thisData=thisTrObj.eq(i).attr("data-matchid");
				if(thisData)matchId.push(thisData);
		}

		var url = "/ajax/?method=data.match.endodds";
		var data = "bettingTypeId="+thisVal[0]+"&providerId="+thisVal[1]+"&matchId="+matchId.join(",");
		if(thisVal.length>=2 && thisVal[2]){
				url = "/ajax/?method=data.match.odds";
				data = "bettingTypeId="+thisVal[0]+"&providerId="+thisVal[1]+"&matchIds="+matchId.join(",")
		}
		
		$.ajax({
				url: url,
				dataType:"json",
				data:data,
				type:"POST",
				success:function(msg){
						if(thisVal.length>=2 && thisVal[2]){
								msg=msg;
						}else{
								msg=msg.match_endodds_response;
						}                
						for(var i=0,ilen=thisTrObj.length;i<ilen;i++){
								var thisMatchId=thisTrObj.eq(i).attr("data-matchid");
								if(!thisMatchId)continue;
								var thisAttr = thisTrObj.eq(i).attr("attr");
								var thisDrawData = thisAttr.split(",");
								var thisData=msg[thisMatchId];
								if(!thisData){
										thisData=["-","-","-"];
								};
								
								var tdObj=thisTrObj.eq(i).find("td");
								var bfVal =thisDrawData[1] ? thisDrawData[1].split("-"):"";
								if(thisId.indexOf("qhpl")>-1){
										tdObj.eq(6).html(thisData[0] || "-");
										tdObj.eq(7).html(thisData[1] || "-");
										tdObj.eq(8).html(thisData[2] || "-");
										if(bfVal && Number(bfVal[0])-Number(bfVal[1])>0){
												tdObj.eq(6).html('<span style="background: none repeat scroll 0% 0% rgb(255, 255, 181);">'+tdObj.eq(6).html()+'</span>');
										}else if(bfVal && Number(bfVal[0])-Number(bfVal[1])==0){
												tdObj.eq(7).html('<span style="background: none repeat scroll 0% 0% rgb(255, 255, 181);">'+tdObj.eq(7).html()+'</span>');
										}else if(bfVal && Number(bfVal[0])-Number(bfVal[1])<0){
												tdObj.eq(8).html('<span style="background: none repeat scroll 0% 0% rgb(255, 255, 181);">'+tdObj.eq(8).html()+'</span>');
										}
								}else if(thisId.indexOf("qhpk")>-1){
										tdObj.eq(9).html(thisData[0] || "-");
										tdObj.eq(10).html(thisData[1] || "-");
										tdObj.eq(11).html(thisData[2] || "-");

										if(thisId.indexOf("home")>-1){
												var drawKey = jQuery.inArray(host_team_id,thisDrawData);
										}else if(thisId.indexOf("away")>-1){
												var drawKey = jQuery.inArray(guest_team_id,thisDrawData);
										}else if(thisId.indexOf("vs")>-1){
												var drawKey = jQuery.inArray(host_team_id,thisDrawData);
										}
										if(typeof(thisData[3])=="undefined"  || thisData[3] == null){
												tdObj.eq(12).html('-');
										}else if(bfVal && Number(bfVal[0])-Number(bfVal[1])+Number(thisData[3])>0){
												tdObj.eq(9).html('<span style="background: none repeat scroll 0% 0% rgb(255, 255, 181);">'+tdObj.eq(9).html()+'</span>');
												if(drawKey==0){
														tdObj.eq(12).html('<span class="redB">赢</span>');
												}else if(drawKey==2){
														tdObj.eq(12).html('<span class="blueB">输</span>');
												}
										}else if(bfVal && Number(bfVal[0])-Number(bfVal[1])+Number(thisData[3])==0){
												tdObj.eq(12).html('<span class="huiB">走</span>');
										}else if(bfVal && Number(bfVal[0])-Number(bfVal[1])+Number(thisData[3])<0){
												tdObj.eq(11).html('<span style="background: none repeat scroll 0% 0% rgb(255, 255, 181);">'+tdObj.eq(11).html()+'</span>');
												if(drawKey==0){
														tdObj.eq(12).html('<span class="blueB">输</span>');
												}else if(drawKey==2){
														tdObj.eq(12).html('<span class="redB">赢</span>');
												}
										}
								}
						}
				}
		});
		// 发生change事件后，声明变量，判断类型，调用SetCookie
		var matchHistoryObj = {
			homeqhplObj: "soccer_match_history_odds_home",
			homeqhpkObj: "soccer_match_history_panlu_home",
			awayqhplObj: "soccer_match_history_odds_away",
			awayqhpkObj: "soccer_match_history_panlu_away",
			vsqhplObj: "soccer_match_history_odds_vs",
			vsqhpkObj: "soccer_match_history_panlu_vs"
		}
		// 设置过期时间(秒)
		// SetCookie(matchHistoryObj[thisId], this.value, 3600*24*30, "/", ".okooo.com");
	});

	// 使用整合接口一次性调用赋值
	function initHistoryData () {
		var matchHistoryObj = {
			"soccer_match_history_odds_home" : 'homeqhplObj',
			"soccer_match_history_panlu_home" : 'homeqhpkObj',
			"soccer_match_history_odds_away" : 'awayqhplObj',
			"soccer_match_history_panlu_away" : 'awayqhpkObj',
			"soccer_match_history_odds_vs" : 'vsqhplObj',
			"soccer_match_history_panlu_vs" : 'vsqhpkObj'
		};
		var defaultObj = {
			'soccer_match_history_odds_home': '1_24',
			'soccer_match_history_panlu_home': '2_27',
			'soccer_match_history_odds_away': '1_24',
			'soccer_match_history_panlu_away': '2_27',
			'soccer_match_history_odds_vs': '1_24',
			'soccer_match_history_panlu_vs': '2_27'
		};
		var data = {
			homeMatchIds: [],
			awayMatchIds: [],
			vsMatchIds: [],
			homeProviderIds: [],
			awayProviderIds: [],
			vsProviderIds: []
		};
		// 遍历处理params数据
		for (var i in matchHistoryObj) {
			// var val = GetCookie(i);
			var val = '';
			var focus = $("#" + matchHistoryObj[i]);
			if (val != '') {
				focus.val(val);
			} else {
				focus.val(defaultObj[i]);
			}
			var focusVal = focus.val().split("_");
			var thisTrObj = tableObj[matchHistoryObj[i]].find("tr");
			if (i.indexOf("home") >= 0) {
				// 处理providerid
				data.homeProviderIds[focusVal[0] - 1] = focusVal[1] + (focusVal[2] ? ('_' + focusVal[2]) : '');
				// 处理matchid
				// 因为两个id指向的table是一样的 所以只取一组即可
				if (data.homeMatchIds.length == 0) {
					for(var i = 0; i < thisTrObj.length; i++){
						var thisData = thisTrObj.eq(i).attr("data-matchid");
						if (thisData) {
							data.homeMatchIds.push(thisData);
						}
					}
				}
			} else if (i.indexOf("away") >= 0) {
				// 处理providerid
				data.awayProviderIds[focusVal[0] - 1] = focusVal[1] + (focusVal[2] ? ('_' + focusVal[2]) : '');
				// 处理matchid
				// 因为两个id指向的table是一样的 所以只取一组即可
				if (data.awayMatchIds.length == 0) {
					for(var i = 0; i < thisTrObj.length; i++){
						var thisData = thisTrObj.eq(i).attr("data-matchid");
						if (thisData) {
							data.awayMatchIds.push(thisData);
						}
					}
				}
			} else if (i.indexOf("vs") >= 0) {
				// 处理providerid
				data.vsProviderIds[focusVal[0] - 1] = focusVal[1] + (focusVal[2] ? ('_' + focusVal[2]) : '');
				// 处理matchid
				// 因为两个id指向的table是一样的 所以只取一组即可
				if (data.vsMatchIds.length == 0) {
					for(var i = 0; i < thisTrObj.length; i++){
						var thisData = thisTrObj.eq(i).attr("data-matchid");
						if (thisData) {
							data.vsMatchIds.push(thisData);
						}
					}
				}
			}
		};
		var param = {
			homeMatchIds: data.homeMatchIds.join(","),
			awayMatchIds: data.awayMatchIds.join(","),
			vsMatchIds: data.vsMatchIds.join(","),
			homeProviderIds: data.homeProviderIds.join(","),
			awayProviderIds: data.awayProviderIds.join(","),
			vsProviderIds: data.vsProviderIds.join(",")
		};
		$.ajax({
			url: '/I/?method=ok.soccer.odds.getAllOdds',
			dataType: "json",
			data: param,
			type: "POST",
			success:function(res){
				var regObj = {
					"soccer_match_history_odds_home" : 'homeOdds',
					"soccer_match_history_panlu_home" : 'homeHandicap',
					"soccer_match_history_odds_away" : 'awayOdds',
					"soccer_match_history_panlu_away" : 'awayHandicap',
					"soccer_match_history_odds_vs" : 'vsOdds',
					"soccer_match_history_panlu_vs" : 'vsHandicap'
				};
				for (var m in matchHistoryObj) {
					var thisTrObj = tableObj[matchHistoryObj[m]].find("tr");
					var thisId=matchHistoryObj[m];
					for(var i=0,ilen=thisTrObj.length;i<ilen;i++){
						var thisMatchId=thisTrObj.eq(i).attr("data-matchid");
						if(!thisMatchId)continue;
						var thisAttr = thisTrObj.eq(i).attr("attr");
						var thisDrawData = thisAttr.split(",");
						var thisData=res[regObj[m]][thisMatchId];
						if(!thisData){
							thisData=["-","-","-"];
						};
						var tdObj=thisTrObj.eq(i).find("td");
						var bfVal =thisDrawData[1] ? thisDrawData[1].split("-"):"";
						if(thisId.indexOf("qhpl")>-1){
							tdObj.eq(6).html(thisData[0] || "-");
							tdObj.eq(7).html(thisData[1] || "-");
							tdObj.eq(8).html(thisData[2] || "-");
							if(bfVal && Number(bfVal[0])-Number(bfVal[1])>0){
								tdObj.eq(6).html('<span style="background: none repeat scroll 0% 0% rgb(255, 255, 181);">'+tdObj.eq(6).html()+'</span>');
							}else if(bfVal && Number(bfVal[0])-Number(bfVal[1])==0){
								tdObj.eq(7).html('<span style="background: none repeat scroll 0% 0% rgb(255, 255, 181);">'+tdObj.eq(7).html()+'</span>');
							}else if(bfVal && Number(bfVal[0])-Number(bfVal[1])<0){
								tdObj.eq(8).html('<span style="background: none repeat scroll 0% 0% rgb(255, 255, 181);">'+tdObj.eq(8).html()+'</span>');
							}
						}else if(thisId.indexOf("qhpk")>-1){
							tdObj.eq(9).html(thisData[0] || "-");
							tdObj.eq(10).html(thisData[1] || "-");
							tdObj.eq(11).html(thisData[2] || "-");
	
							if(thisId.indexOf("home")>-1){
								var drawKey = jQuery.inArray(host_team_id,thisDrawData);
							}else if(thisId.indexOf("away")>-1){
								var drawKey = jQuery.inArray(guest_team_id,thisDrawData);
							}else if(thisId.indexOf("vs")>-1){
								var drawKey = jQuery.inArray(host_team_id,thisDrawData);
							}
							if(typeof(thisData[3])=="undefined"  || thisData[3] == null){
								tdObj.eq(12).html('-');
							}else if(bfVal && Number(bfVal[0])-Number(bfVal[1])+Number(thisData[3])>0){
								tdObj.eq(9).html('<span style="background: none repeat scroll 0% 0% rgb(255, 255, 181);">'+tdObj.eq(9).html()+'</span>');
								if(drawKey==0){
									tdObj.eq(12).html('<span class="redB">赢</span>');
								}else if(drawKey==2){
									tdObj.eq(12).html('<span class="blueB">输</span>');
								}
							}else if(bfVal && Number(bfVal[0])-Number(bfVal[1])+Number(thisData[3])==0){
								tdObj.eq(12).html('<span class="huiB">走</span>');
							}else if(bfVal && Number(bfVal[0])-Number(bfVal[1])+Number(thisData[3])<0){
								tdObj.eq(11).html('<span style="background: none repeat scroll 0% 0% rgb(255, 255, 181);">'+tdObj.eq(11).html()+'</span>');
								if(drawKey==0){
									tdObj.eq(12).html('<span class="blueB">输</span>');
								}else if(drawKey==2){
									tdObj.eq(12).html('<span class="redB">赢</span>');
								}
							}
						}
					}
				}
			}
		});

	}
	if (location.href.indexOf("history")) {
		// initHistoryData();
	}
});

/**
 * 动态显示表格。
 */
function showTable(ID,TotalCount)
{
		for (var I = 1; I <= TotalCount; I++)
		{
				if (I == ID)
				{
						document.getElementById("Table" + I).style.display = "";
				}
				else
				{
						document.getElementById("Table" + I).style.display = "none";
				}
		}
} 
