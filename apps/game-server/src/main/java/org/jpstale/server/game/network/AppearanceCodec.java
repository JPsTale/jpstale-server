package org.jpstale.server.game.network;

import org.jpstale.server.common.model.CharacterAppearance;
import org.jpstale.server.proto.base.CommonProto;

/**
 * {@link CharacterAppearance}（纯模型）→ protobuf 的**唯一**适配点。
 *
 * 为什么要这一层：外观是纯数据（要被 common-service / web-server 复用），
 * 而线上类型是 protobuf（只有 game-server 依赖）。两者之间只在网络边界转换一次，
 * 免得"域模型里塞着 wire 类型"（那样 Player 就离不开 protobuf 了）。
 *
 * 转换是全字段无条件 set：proto3 里"设置成默认值"与"不设置"在线上字节与
 * getter 取值上都相同，所以这样与旧的"逐字段判空后再 set"行为完全等价，但少一半分支。
 */
public final class AppearanceCodec {

    private AppearanceCodec() {
    }

    public static CommonProto.CharacterAppearance toProto(CharacterAppearance a) {
        if (a == null) {
            return CommonProto.CharacterAppearance.getDefaultInstance();
        }
        return CommonProto.CharacterAppearance.newBuilder()
                .setClassId(a.getClassId())
                .setHead(a.getHead())
                .setRank(a.getRank())
                .setBodyModel(a.getBodyModel() == null ? "" : a.getBodyModel())
                .setBodyModelIdcode(a.getBodyModelIdcode())
                .setWeaponDorp(a.getWeaponDorp() == null ? "" : a.getWeaponDorp())
                .setWeaponIdcode(a.getWeaponIdcode())
                .setWeaponPos(a.getWeaponPos())
                .setOffHandDorp(a.getOffHandDorp() == null ? "" : a.getOffHandDorp())
                .setOffHandIdcode(a.getOffHandIdcode())
                .setOffHandKind(a.getOffHandKind())
                .setOffHandPos(a.getOffHandPos())
                .build();
    }
}
