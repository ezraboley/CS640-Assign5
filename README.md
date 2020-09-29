# Flow Control and DNS
## Overview

For this assignment, you will first implement a Python-based data sender and receiver using the sliding window algorithm. Then you will write your own simple Java-based DNS server that performs recursive DNS resolutions, and appends a special annotation if an IP address belongs to an Amazon EC2 region.
- Part 1: Flow Control
- Part 2: Simple DNS Server
### Learning objectives

After completing this assignment, you should be able to:
- Explain how the sliding window algorithm facilitates flow control
- Explain how the domain name system (DNS) works

## Useful commands
DNS to local server

`dig +norecurse -p 8053 @localhost A www.google.com`
