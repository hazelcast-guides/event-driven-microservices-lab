#//# --------------------------------------------------------------------------------------
#//# Created using Sequence Diagram for Mac
#//# https://www.macsequencediagram.com
#//# https://itunes.apple.com/gb/app/sequence-diagram/id1195426709?mt=12
#//# --------------------------------------------------------------------------------------
participant "txn 1" as t1
participant "txn 2" as t2
participant "account \nlimit=100 spend=90" as a

t1->a: check spend vs limit ($10 remaining)

t2->a: check spend vs limit ($10 remaining)

t1->a: update spend (spend=$100 limit=$100)

t2->a: update spend (spend=$110 limit=$100)



