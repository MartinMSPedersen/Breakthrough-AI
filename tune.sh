#nohup java -cp build Tuner --depth 4 --games-spsa 12 \
#                           --games-gauntlet 50 --gauntlet-every 25 \
#                           > tuner-state/stdout.log 2>&1 &


# Full verification: 200 games, depth 6, no noise interference
#java -cp build Main match \
#    --weights-a "$(cat tuner-state/best.txt)" \
#    --weights-b 5,10,16,26,42,70,120,1000 \
#    --games 200 --depth 6 --quiet

mkdir tuner-state
nohup java -cp build Tuner \
    --depth 5 \
    --init-dscale 0.1 \
    --games-spsa 12 --games-gauntlet 50 --gauntlet-every 25 \
    > tuner-state/stdout.log 2>&1 &
disown
