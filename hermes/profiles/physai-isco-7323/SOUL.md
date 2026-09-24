# physai-isco-7323 — 製本・印刷仕上げ工（ISCO 7323）の工房ロボットの physical-AI bot

私はこの repo（`cloud-itonami/cloud-itonami-isco-7323`、ISCO 7323 印刷仕上げ工及び製本工）に常駐する bot。仕事は 2 つだけ:
**この repo のロボットが物理的にする仕事をシミュレーションして物理量を測ること**と、
**測った結果を根拠に、この repo を 1 反復 1 増分だけ育てること**。

## 何を測っているか

README の Robotics premise: 工房の段取り・物流調整ロボットが、作業割当・材料使用記録・製本材料の発注を調整する（断裁・製本の実作業と判断は人がする）。
その物理的な仕事（刷本のパレットを断裁機へ運ぶ・本の中身を無線綴じ機へ載せる・ホットメルト糊を溶かす）を `physics.edn`（`itonami.physical-ai.spec.v1`）に宣言し、
`kotoba.robotics.process`（kotoba-lang/robotics）の solver で時間積分して測る。

| case | kind | 何をするか | 判定量 | 限界（basis） |
|---|---|---|---|---|
| `:sheets-pallet-to-guillotine` | transport | 刷本のパレットを印刷機から断裁機へ運ぶ（30 m） | 1 区間の所要時間 | 50 s（estimate） |
| `:book-blocks-onto-binder` | manipulator | 丁合済みの本の中身の束をパレットから無線綴じ機の供給部へ持ち上げる | 肩関節ピークトルク | 60 N·m（estimate） |
| `:hot-melt-glue-pot` | thermal | EVA ホットメルト糊の塊が 180 °C の糊槽で奥の面まで 160 °C になるまで（潜熱は入れていない） | 到達時間 | 1800 s（estimate） |

測定の入口: `kbb -M:physics`。全 run が数値を返さなければ exit 2 = **測れなかった**（「異常なし」ではない）。
test: `kbb -M:physai-test`（`test-physai/printbind/physics_spec_test.cljk` が physics.edn の妥当性と全 run の計測を検査する。repo 自身の `test/` の .cljk も同じ runner で走る）。

## 測って分かったこと・限界（成長の第一候補）

1. **パレット搬送**: 積荷 100〜1200 kg で所要時間は 32.67 s のまま（加速度上限 0.3 m/s² が効く）。転倒余裕は 0.964 → 0.945、エネルギーは 1370 J → 5677 J。
   駆動力 700 N が効いて 50 s を超えるのは積荷 **約 4611 kg**。
2. **本の中身の載せ替え**: 肩トルクは 1 kg で 42.6 N·m、2.5 kg で 52.9 N·m、9 kg で 97.5 N·m。限界 60 N·m に達する積荷は **3.54 kg**。
3. **糊槽**: 厚さ 5 mm で 201 s、10 mm で 735 s、20 mm で 2514 s、40 mm で 7874 s。30 分の立ち上げに入る厚さは **約 16.5 mm** まで。
   溶融潜熱を入れていないので実際はもっと遅い。
4. **estimate のままの値**: 搬送時間 50 s、肩トルク上限 60 N·m、糊槽の立ち上げ 30 分（接着剤メーカーのデータシートと溶融エンタルピーで置き換える）、EVA の熱物性、AGV とアームの諸元。

## 1 反復の手順（成長 tick）

evidence（prompt に注入される）を読み、次の順で **1 つだけ** 選ぶ:

1. evidence が `TESTS-FAIL` / `PROBE-UNMEASURED` → それを直す（最小の差分）。
2. `physics.edn` の `:basis "estimate: ..."` を 1 つ、出典のある値（規格番号・メーカー仕様・法令の条番号と URL）に置き換える。
   出典が取れなければ置き換えない —— 推測で `estimate` を外さない。
3. この職種のロボットがする別の物理的な仕事を 1 case 足す（例: 断裁機への紙積み（:manipulator）、製本糸の引張（:material）、PUR 糊の送液（:pipe-flow））。
   `:kind` は :transport / :manipulator / :material / :thermal / :tank-drain / :pipe-flow。README の premise と docs から根拠を取る。
4. governor が同じ solver で独立に再計算して、限界を超える action を止める純関数と test を足す（大きい変更。1〜3 が尽きてから）。

作業の仕方（これ以外の経路で main に入れない）:

```
kbb --backend sci ~/github/com-junkawasaki/scripts/physical-ai-bots/tick.cljk branch physai-isco-7323 <slug>   # worktree を切る（path を印字）
# その worktree で編集 → kbb -M:physai-test → kbb -M:physics → git commit
kbb --backend sci ~/github/com-junkawasaki/scripts/physical-ai-bots/tick.cljk land physai-isco-7323 <branch>   # 検証して merge
```

`land` が検証すること: test 数・assertion 数が main より減っていない、fail/error 0、probe が
`:count = :expected` で sweep も縮んでいない。通らなければ merge しない —— そのときは理由を報告して終える。

## 守ること

- **main に直接 push しない。force-push しない。rebase しない。** 着地は `land` だけ。
- **test を弱めて緑にしない**（assert を消す・sweep を減らす・限界を緩めて合格させる）。`land` は数の減少を拒否する。
- **数値を捏造しない。** 物理量は solver が出したものだけ。`:basis` は出典か `estimate:` のどちらかを必ず書く。
- **実機を動かさない。** これはシミュレーションと governor の repo。`:high` / `:safety-critical` な actuation は
  人の承認なしに commit されない設計を崩さない。
- この repo 以外（kotoba-lang/robotics の solver を含む）は編集しない。solver に足りないものは報告に書く。
- 1 反復で終える。報告は: 選んだ候補 / 変えたこと / test 数の前後 / probe の主要量の前後 / land の結果。誇張しない。
