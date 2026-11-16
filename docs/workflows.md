# Detailed operational workflows

These describe implemented baseline paths; gateway waiting-room behavior is a deployment requirement.

## Hot SKU handling

```mermaid
flowchart TD
    Burst --> Gateway[Waiting room and gateway shedding]
    Gateway --> Quota[Redis sale quota]
    Quota --> Slots[32 local slots]
    Slots --> Pool[16 DB connections]
    Pool --> Row[One serialized authoritative pool]
```

## Redis Lua execution

```mermaid
flowchart TD
 Request --> Slot[Sale hash slot]
 Slot --> Check[Read two counters]
 Check -->|under quota| Increment[Increment both and set TTL]
 Check -->|over quota| Reject[Reject without changing counters]
 Increment --> Permit[Permit attempt only]
```

## PostgreSQL reservation

```mermaid
flowchart TD
    Begin --> Sale[Lock sale SHARE]
    Sale --> Idem[Claim unique operation key]
    Idem --> Allocation[Lock allocation]
    Allocation --> Usage[Conditional customer increment]
    Usage --> Stock[Conditional reserved increment]
    Stock --> Facts[Reservation and movement and outbox]
    Facts --> Commit
```

## Confirmation

```mermaid
flowchart TD
    Confirm --> Lock[Lock reservation]
    Lock --> Due{Due or terminal?}
    Due -->|due| Expire[Expire and release once]
    Due -->|terminal| Return[Return current state]
    Due -->|active| Transfer[Reserved to committed]
    Transfer --> Event[State and ledger and outbox commit]
```

## Cancellation

```mermaid
flowchart TD
    Cancel --> Lock[Lock reservation]
    Lock --> State{RESERVED?}
    State -->|yes| Release[Release pool and customer usage]
    Release --> Commit[Terminal state and ledger and outbox commit]
    State -->|no| Return[Return unchanged terminal state]
```

## Expiration

```mermaid
flowchart TD
 Scan[Bounded timestamp scan] --> ID[Reservation UUID]
 ID --> Lock[Lock and check state/version/deadline]
 Lock -->|active and due| Release[Release exactly once]
 Lock -->|already terminal| Ignore[No mutation]
 Release --> Commit[Commit terminal state and event]
```

## Kafka processing

```mermaid
flowchart TD
    State[Inventory transaction] --> Outbox[Durable outbox]
    Outbox --> Publish[Publish with acknowledgement]
    Publish --> Mark[Mark published]
    Publish -->|crash before mark| Duplicate[Republish same event ID]
    Duplicate --> Inbox[Inbox unique event ID]
    Mark --> Inbox
    Inbox --> Version[Apply only newer aggregate version]
```

## Redis failure

```mermaid
flowchart TD
 Try --> Redis[Redis timeout or lost quota]
 Redis -->|uncertain result| Closed[503 fail closed]
 Redis -->|permitted| PG[PostgreSQL still decides ownership]
 Closed --> Retry[Bounded jitter and same key]
 PG --> Invariant[Authoritative stock invariant]
```

## PostgreSQL failure

```mermaid
flowchart TD
 Mutation --> PG[Primary transaction]
 PG -->|unavailable| Unavailable[503 no ownership claim]
 PG -->|commit response lost| Unknown[Ambiguous client outcome]
 Unknown --> Retry[Retry original operation key]
 Retry --> Lookup[Return committed result or execute rolled-back operation]
```

## Inventory reconciliation

```mermaid
flowchart TD
    Tick[Scheduled or admin trigger] --> Batch[Lock oldest-audited allocation batch]
    Batch --> Sums[Sum authoritative reservation states]
    Sums --> Compare{Counters agree?}
    Compare -->|yes| Consistent[Persist audit record]
    Compare -->|no| Review[Persist manual-review record and alert]
    Review --> Freeze[Freeze sale and investigate ledger/orders]
```

