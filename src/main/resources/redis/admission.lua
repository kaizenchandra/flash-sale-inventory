-- All keys share a sale hash slot. Admission is not an inventory grant.
-- Fixed one-second windows: at most twice the nominal rate around a boundary.
local customer = tonumber(redis.call('GET', KEYS[2]) or '0')
local sale = tonumber(redis.call('GET', KEYS[1]) or '0')
if customer >= tonumber(ARGV[2]) then return 2 end
if sale >= tonumber(ARGV[1]) then return 1 end
redis.call('INCR', KEYS[1]); redis.call('PEXPIRE', KEYS[1], 2000)
redis.call('INCR', KEYS[2]); redis.call('PEXPIRE', KEYS[2], 2000)
return 0
