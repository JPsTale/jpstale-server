package org.jpstale.server.common.redis;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CommonMsg implements Serializable {

    private String requestId;
    private String type;
    private String data;

    public CommonMsg(String type, String data) {
        this.type = type;
        this.data = data;
    }
}
