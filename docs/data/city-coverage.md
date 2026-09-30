# R3 城市覆盖清单

本清单对应已冻结的首批十城范围。S06 只建立字段、来源台账和北京最小合成样例；其余城市保持规划状态，不用占位数据冒充已覆盖事实。

| city_id | 展示名称 | S06 状态 | R3 数据要求 |
|---|---|---|---|
| beijing | 北京 | sample_ready | 先覆盖 S05 R3 案例所需的景点、POI 和预约字段 |
| shanghai | 上海 | planned | 完成来源台账后再进入 draft |
| hangzhou | 杭州 | planned | 完成来源台账后再进入 draft |
| nanjing | 南京 | planned | 完成来源台账后再进入 draft |
| suzhou | 苏州 | planned | 完成来源台账后再进入 draft |
| chengdu | 成都 | planned | 完成来源台账后再进入 draft |
| xian | 西安 | planned | 完成来源台账后再进入 draft |
| chongqing | 重庆 | planned | 完成来源台账后再进入 draft |
| guangzhou | 广州 | planned | 完成来源台账后再进入 draft |
| xiamen | 厦门 | planned | 完成来源台账后再进入 draft |

city_id 是机器关联键，展示名称不能作为 POI、知识或规则的唯一关联键。未覆盖城市或未核验事实必须由 Agent 返回 unknown 或显式降级。
