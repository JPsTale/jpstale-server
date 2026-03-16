package org.jpstale.server.web.clan;

import org.jpstale.dao.clandb.entity.Cl;
import org.jpstale.dao.clandb.entity.ClanList;
import org.jpstale.dao.clandb.entity.Li;
import org.jpstale.dao.clandb.entity.Ul;
import org.jpstale.dao.clandb.mapper.ClMapper;
import org.jpstale.dao.clandb.mapper.ClanListMapper;
import org.jpstale.dao.clandb.mapper.LiMapper;
import org.jpstale.dao.clandb.mapper.UlMapper;
import org.jpstale.dao.userdb.mapper.CharacterInfoMapper;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * ClanSystem 17 个接口业务逻辑，与原版 ASP 行为与 Code 一致。
 */
@Service
public class ClanService {

    private static final DateTimeFormatter REGI_LIMIT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    private final ClMapper clMapper;
    private final UlMapper ulMapper;
    private final ClanListMapper clanListMapper;
    private final LiMapper liMapper;
    private final CharacterInfoMapper characterInfoMapper;

    public ClanService(ClMapper clMapper, UlMapper ulMapper, ClanListMapper clanListMapper,
                       LiMapper liMapper, CharacterInfoMapper characterInfoMapper) {
        this.clMapper = clMapper;
        this.ulMapper = ulMapper;
        this.clanListMapper = clanListMapper;
        this.liMapper = liMapper;
        this.characterInfoMapper = characterInfoMapper;
    }

    // ---------- 简单校验接口 ----------

    public ClanResponse checkClanPlayer(String clwon, String gserver) {
        return ClanResponse.of(1);
    }

    public ClanResponse checkDate(String chname) {
        String clanName = chname != null ? ulMapper.selectClanNameByChName(chname.trim()) : null;
        if (clanName == null || clanName.isEmpty()) return ClanResponse.of(0);
        return ClanResponse.of(1);
    }

    public ClanResponse checkUnknown(String chname) {
        return checkDate(chname);
    }

    public ClanResponse checkClanName(String clName, String gserver) {
        String zang = clName != null ? clMapper.selectClanZangByClanName(clName.trim()) : null;
        if (zang == null) return ClanResponse.of(0);
        return ClanResponse.of(1);
    }

    public ClanResponse checkClanId(Integer num, String gserver) {
        if (num == null) return ClanResponse.of(0);
        Cl cl = clMapper.selectClanNameNoteByMIconCnt(num);
        if (cl == null) return ClanResponse.of(0);
        ClanResponse r = ClanResponse.of(1);
        r.put("CName", cl.getClanName());
        r.put("CNote", cl.getNote() != null ? cl.getNote() : "");
        return r;
    }

    public ClanResponse checkClanLeader(String userid, String gserver) {
        String clanName = userid != null ? clMapper.selectClanNameByUserId(userid.trim()) : null;
        if (clanName == null || clanName.isEmpty()) return ClanResponse.of(0);
        return ClanResponse.of(1);
    }

    // ---------- NewClan ----------

    public ClanResponse newClan(String userid, String gserver, String chname, String clName, Integer chtype, Integer lv) {
        String chnameTrim = chname != null ? chname.trim() : "";
        String clNameTrim = clName != null ? clName.trim() : "";
        String existingClan = ulMapper.selectClanNameByChName(chnameTrim);
        if (existingClan != null && !existingClan.isEmpty()) {
            ClanResponse r = ClanResponse.of(2);
            r.put("CMoney", "0");
            return r;
        }
        if (existingClan != null) ulMapper.deleteByChName(chnameTrim);
        if (clMapper.selectClanZangByClanName(clNameTrim) != null) {
            ClanResponse r = ClanResponse.of(3);
            r.put("CMoney", "0");
            return r;
        }
        Integer iIMG = liMapper.selectImgById(1);
        if (iIMG == null) {
            Li li = new Li();
            li.setId(1);
            li.setImg(1);
            liMapper.insertLi(li);
            iIMG = 1;
        }
        int nextImg = iIMG + 1;
        liMapper.updateImgById(1, nextImg);

        Cl cl = new Cl();
        cl.setClanName(clNameTrim);
        cl.setUserId(userid != null ? userid.trim() : "");
        cl.setClanZang(chnameTrim);
        cl.setMemCnt(1);
        cl.setNote("RenaissancePT");
        cl.setMIconCnt(nextImg);
        cl.setDelActive("0");
        cl.setPFlag(0);
        cl.setKFlag(0);
        cl.setFlag(0);
        cl.setNoteCnt(0);
        cl.setCPoint(0);
        cl.setCWin(0);
        cl.setCFail(0);
        cl.setClanMoney(0L);
        cl.setCnFlag(0);
        cl.setSiegeMoney(0L);
        clMapper.insertCl(cl);

        Integer idx = clMapper.selectIdByClanName(clNameTrim);
        Ul ul = new Ul();
        ul.setClanId(idx);
        ul.setUserId(userid != null ? userid.trim() : "");
        ul.setChName(chnameTrim);
        ul.setClanName(clNameTrim);
        ul.setChType(chtype != null ? chtype : 0);
        ul.setChLv(lv != null ? lv : 0);
        ul.setPermi("0");
        ul.setDelActive("0");
        ul.setPFlag(0);
        ul.setKFlag(0);
        ul.setMIconCnt(nextImg);
        ulMapper.insertUl(ul);

        ClanList clanList = new ClanList();
        clanList.setClanName(clNameTrim);
        clanList.setClanLeader(chnameTrim);
        clanList.setNote("RenaissancePT");
        clanList.setAccountName(userid != null ? userid.trim() : "");
        clanList.setMembersCount(1);
        clanList.setIconId(0);
        clanList.setRegisDate(1);
        clanList.setLimitDate(10);
        clanList.setDeleteActive(0);
        clanList.setFlag(0);
        clanList.setSiegeWarPoints(0);
        clanList.setBellatraPoints(0);
        clanList.setSiegeWarGold(0);
        clanList.setBellatraGold(0);
        clanList.setBellatraDate(0L);
        clanList.setLoginMessage("");
        clanListMapper.insertClanList(clanList);

        Integer clanListId = clanListMapper.selectIdByClanName(clNameTrim);
        if (clanListId != null) characterInfoMapper.updateClanIdByName(chnameTrim, clanListId);

        ClanResponse r = ClanResponse.of(1);
        r.put("CMoney", "0");
        return r;
    }

    // ---------- DeleteClan ----------

    public ClanResponse deleteClan(String userid, String gserver, String chname, String clName) {
        List<ClanList> list = clanListMapper.selectByAccountName(userid != null ? userid.trim() : "");
        if (list == null || list.size() != 1) return ClanResponse.of(0);
        String leader = clanListMapper.selectClanLeaderByClanName(clName != null ? clName.trim() : "");
        if (leader == null || !leader.equals(chname != null ? chname.trim() : "")) return ClanResponse.of(0);
        String clNameTrim = clName.trim();
        clanListMapper.deleteByClanName(clNameTrim);
        clMapper.deleteByClanName(clNameTrim);
        ulMapper.deleteByClanName(clNameTrim);
        characterInfoMapper.updateClanIdByName(chname.trim(), 0);
        return ClanResponse.of(1);
    }

    // ---------- LeavePlayer / LeavePlayerSelf ----------

    public ClanResponse leavePlayer(String userid, String gserver, String chname, String clName, String clwon1, String ticket) {
        String clwon1Trim = clwon1 != null ? clwon1.trim() : "";
        String clNameTrim = clName != null ? clName.trim() : "";
        String targetClan = ulMapper.selectClanNameByChName(clwon1Trim);
        if (targetClan == null || targetClan.isEmpty()) return ClanResponse.of(0);
        if (!targetClan.equals(clNameTrim)) return ClanResponse.of(0);
        Cl cl = clMapper.selectIdClanZangMemCntByClanName(clNameTrim);
        if (cl == null) return ClanResponse.of(0);
        if (clwon1Trim.equals(cl.getClanZang())) return ClanResponse.of(4);
        int newCnt = (cl.getMemCnt() != null ? cl.getMemCnt() : 1) - 1;
        clMapper.updateMemCntByClanName(newCnt, clNameTrim);
        ulMapper.deleteByChName(clwon1Trim);
        return ClanResponse.of(1);
    }

    public ClanResponse leavePlayerSelf(String userid, String gserver, String chname, String clName) {
        String chnameTrim = chname != null ? chname.trim() : "";
        String clNameTrim = clName != null ? clName.trim() : "";
        Ul ul = ulMapper.selectClanNameAndPermiByChName(chnameTrim);
        if (ul == null || ul.getClanName() == null || ul.getClanName().isEmpty()) return ClanResponse.of(0);
        if (!ul.getClanName().equals(clNameTrim)) return ClanResponse.of(0);
        Cl cl = clMapper.selectIdClanZangMemCntByClanName(clNameTrim);
        if (cl == null) return ClanResponse.of(0);
        if (chnameTrim.equals(cl.getClanZang())) return ClanResponse.of(4);
        int newCnt = (cl.getMemCnt() != null ? cl.getMemCnt() : 1) - 1;
        clMapper.updateMemCntByClanName(newCnt, clNameTrim);
        ulMapper.deleteByChName(chnameTrim);
        return ClanResponse.of(1);
    }

    // ---------- InviteClan ----------

    public ClanResponse inviteClan(String userid, String gserver, String chname, String clName,
                                   String clwon, String clwonUserid, Integer lv, Integer chtype, Integer chlv, String chipflag) {
        String clNameTrim = clName != null ? clName.trim() : "";
        String chnameTrim = chname != null ? chname.trim() : "";
        String clwonTrim = clwon != null ? clwon.trim() : "";
        Cl cl = clMapper.selectIdClanZangMemCntByClanName(clNameTrim);
        if (cl == null) return ClanResponse.of(0);
        int memCnt = cl.getMemCnt() != null ? cl.getMemCnt() : 0;
        if (memCnt + 1 > 100) return ClanResponse.of(2);
        String subChief = ulMapper.selectChNameByPermi2AndClanName(clNameTrim);
        boolean isLeader = chnameTrim.equals(cl.getClanZang());
        boolean isSub = subChief != null && subChief.equals(chnameTrim);
        if (!isLeader && !isSub) return ClanResponse.of(0);
        String uclName = ulMapper.selectClanNameByChName(clwonTrim);
        if (uclName != null && !uclName.isEmpty()) return ClanResponse.of(0);
        ulMapper.deleteByChName(clwonTrim);
        if (memCnt + 1 > 20) return ClanResponse.of(4);
        int newCnt = memCnt + 1;
        clMapper.updateMemCntByClanName(newCnt, clNameTrim);
        Integer idx = cl.getId();
        Ul ul = new Ul();
        ul.setClanId(idx);
        ul.setUserId(clwonUserid != null ? clwonUserid.trim() : "");
        ul.setChName(clwonTrim);
        ul.setClanName(clNameTrim);
        ul.setChType(chtype != null ? chtype : 0);
        ul.setChLv(chlv != null ? chlv : 0);
        ul.setPermi("0");
        ul.setDelActive("0");
        ul.setPFlag(0);
        ul.setKFlag(0);
        ul.setMIconCnt(cl.getMIconCnt() != null ? cl.getMIconCnt() : 0);
        ulMapper.insertUl(ul);
        return ClanResponse.of(1);
    }

    // ---------- ChangeLeader / SubLeaderRelease / SubLeaderUpdate ----------

    public ClanResponse changeLeader(String chname, String gserver, String clName) {
        String chnameTrim = chname != null ? chname.trim() : "";
        String clNameTrim = clName != null ? clName.trim() : "";
        String userId = ulMapper.selectUserIdByChNameAndClanName(chnameTrim, clNameTrim);
        if (userId == null) return ClanResponse.of(0);
        clMapper.updateClanZangAndUserIdByClanName(chnameTrim, userId, clNameTrim);
        return ClanResponse.of(1);
    }

    public ClanResponse subLeaderRelease(String chname, String gserver) {
        String chnameTrim = chname != null ? chname.trim() : "";
        ulMapper.updatePermi0ByChName(chnameTrim);
        return ClanResponse.of(1);
    }

    public ClanResponse subLeaderUpdate(String chname, String gserver) {
        String chnameTrim = chname != null ? chname.trim() : "";
        ulMapper.updatePermi0ByClanNameInChName(chnameTrim);
        ulMapper.updatePermi2ByChName(chnameTrim);
        return ClanResponse.of(1);
    }

    // ---------- GetClanMembers ----------

    public ClanResponse getClanMembers(String userid, String gserver, String chname) {
        String clanName = chname != null ? ulMapper.selectClanNameByChName(chname.trim()) : null;
        if (clanName == null || clanName.isEmpty()) return ClanResponse.of(0);
        String clanZang = clMapper.selectClanZangByClanName(clanName);
        if (clanZang == null) return ClanResponse.of(0);
        ClanResponse r = ClanResponse.of(1);
        r.put("CClanName", clanName);
        r.put("CClanZang", clanZang);
        List<String> members = ulMapper.selectChNameListByClanName(clanName);
        if (members != null) for (String m : members) r.addLine("CMem", m);
        return r;
    }

    // ---------- ClanMember ----------

    public ClanResponse clanMember(String userid, String gserver, String chname) {
        String chnameTrim = chname != null ? chname.trim() : "";
        String clanName = ulMapper.selectClanNameByChName(chnameTrim);
        if (clanName == null || clanName.isEmpty()) {
            ulMapper.deleteByChName(chnameTrim);
            ClanResponse r = ClanResponse.of(0);
            r.put("CMoney", "500000");
            r.put("CNFlag", "0");
            return r;
        }
        Cl cl = clMapper.selectClanZangMemCntNoteMIconCntRegiDateLimitDatePFlagKFlagClanMoneyByClanName(clanName);
        if (cl == null) {
            ulMapper.deleteByChName(chnameTrim);
            ClanResponse r = ClanResponse.of(0);
            r.put("CMoney", "500000");
            r.put("CNFlag", "0");
            return r;
        }
        int cnFlag = 0;
        List<Cl> rankList = clMapper.selectByClanNameOrderByCpointDesc();
        if (rankList != null && cl.getCPoint() != null && cl.getCPoint() > 0) {
            for (int i = 0; i < rankList.size() && i < 3; i++) {
                if (clanName.equals(rankList.get(i).getClanName())) {
                    cnFlag = i + 1;
                    break;
                }
            }
        }
        String subChief = ulMapper.selectChNameByPermi2AndClanName(clanName);
        String cRegiD = cl.getRegiDate() != null ? cl.getRegiDate().format(REGI_LIMIT_FORMAT) : "";
        String cLimitD = cl.getLimitDate() != null ? cl.getLimitDate().format(REGI_LIMIT_FORMAT) : "";
        ClanResponse r = ClanResponse.of(chnameTrim.equals(cl.getClanZang()) ? 2 : (subChief != null && subChief.equals(chnameTrim) ? 5 : 1));
        r.put("CName", clanName);
        r.put("CNote", cl.getNote() != null ? cl.getNote() : "");
        r.put("CZang", cl.getClanZang() != null ? cl.getClanZang() : "");
        r.put("CSubChip", subChief != null ? subChief : "");
        r.put("CStats", "1");
        r.put("CMCnt", String.valueOf(cl.getMemCnt() != null ? cl.getMemCnt() : 0));
        r.put("CIMG", String.valueOf(cl.getMIconCnt() != null ? cl.getMIconCnt() : 0));
        r.put("CSec", "60");
        r.put("CRegiD", cRegiD);
        r.put("CLimitD", cLimitD);
        r.put("CDelActive", "0");
        r.put("CPFlag", String.valueOf(cl.getPFlag() != null ? cl.getPFlag() : 0));
        r.put("CKFlag", String.valueOf(cl.getKFlag() != null ? cl.getKFlag() : 0));
        r.put("CMoney", String.valueOf(cl.getClanMoney() != null ? cl.getClanMoney() : 0));
        r.put("CNFlag", String.valueOf(cnFlag));
        ulMapper.updateMIconCntByChName(chnameTrim, cl.getMIconCnt() != null ? cl.getMIconCnt() : 0);
        return r;
    }

    // ---------- SodScore ----------

    public ClanResponse sodScore(String userid, String gserver, String chname, Integer index) {
        if (index == null) return ClanResponse.of(104);
        String chnameTrim = chname != null ? chname.trim() : "";
        if (index == 1) {
            List<Cl> list = clMapper.selectByClanNameOrderByCpointDesc();
            Cl top = (list != null && !list.isEmpty()) ? list.get(0) : null;
            Ul ul = ulMapper.selectByChName(chnameTrim);
            if (ul == null || ul.getClanName() == null || ul.getClanName().isEmpty()) {
                ClanResponse r = ClanResponse.of(0);
                r.put("CClanMoney", "0");
                r.put("CTax", "0");
                if (top != null) {
                    r.put("CName", top.getClanName());
                    r.put("CNote", top.getNote() != null ? top.getNote() : "");
                    r.put("CZang", top.getClanZang() != null ? top.getClanZang() : "");
                    r.put("CIMG", String.valueOf(top.getMIconCnt() != null ? top.getMIconCnt() : 0));
                }
                return r;
            }
            boolean sameClan = top != null && top.getClanName() != null && top.getClanName().equals(ul.getClanName());
            String subChief = top != null ? ulMapper.selectChNameByPermi2AndClanName(top.getClanName()) : null;
            int code;
            if (sameClan) {
                if (chnameTrim.equals(top.getClanZang())) code = 1;
                else if (subChief != null && subChief.equals(chnameTrim)) code = 2;
                else code = 3;
            } else {
                String myClanZang = clMapper.selectClanZangByClanName(ul.getClanName());
                String mySub = ulMapper.selectChNameByPermi2AndClanName(ul.getClanName());
                if (chnameTrim.equals(myClanZang)) code = 4;
                else if (mySub != null && mySub.equals(chnameTrim)) code = 5;
                else code = 6;
            }
            ClanResponse r = ClanResponse.of(code);
            if (top != null) {
                r.put("CClanMoney", String.valueOf(top.getClanMoney() != null ? top.getClanMoney() : 0));
                r.put("CName", top.getClanName());
                r.put("CNote", top.getNote() != null ? top.getNote() : "");
                r.put("CZang", top.getClanZang() != null ? top.getClanZang() : "");
                r.put("CIMG", String.valueOf(top.getMIconCnt() != null ? top.getMIconCnt() : 0));
            }
            if (code == 1) {
                r.put("TotalEMoney", "0");
                r.put("TotalMoney", "0");
            }
            return r;
        }
        if (index == 3) {
            List<Cl> list = clMapper.selectByClanNameOrderByCpointDesc();
            ClanResponse r = ClanResponse.of(1);
            int count = 0;
            if (list != null) {
                for (Cl c : list) {
                    if (count >= 9) break;
                    if (c.getCPoint() == null || c.getCPoint() <= 0) continue;
                    r.addLine("CIMG", String.valueOf(c.getMIconCnt() != null ? c.getMIconCnt() : 0));
                    r.addLine("CName", c.getClanName());
                    r.addLine("CPoint", String.valueOf(c.getCPoint()));
                    r.addLine("CRegistDay", c.getRegiDate() != null ? c.getRegiDate().toLocalDate().toString() : "");
                    count++;
                }
            }
            return r;
        }
        return ClanResponse.of(104);
    }
}
