# Drawbridge

HTTP transport support for Clojure's
[nREPL](http://github.com/clojure/tools.nrepl) implemented as a
[Ring](http://github.com/mmcgrana/ring) handler.

## Status

This is brand new.  There are surely bugs, and things will break and
change.

## Installation

Drawbridge is available in Clojars. Add this `:dependency` to your Leiningen
`project.clj`:

```clojure
[com.cemerick/drawbridge "0.0.7"]
```

Or, add this to your Maven project's `pom.xml`:

```xml
<repository>
  <id>clojars</id>
  <url>http://clojars.org/repo</url>
</repository>

<dependency>
  <groupId>com.cemerick</groupId>
  <artifactId>drawbridge</artifactId>
  <version>0.0.7</version>
</dependency>
```

Drawbridge is compatible with Clojure 1.2.0 - 1.4.0.

## Usage

While nREPL provides a solid REPL backend for Clojure, typical
socket-based channels are often unsuitable.  Being able to deploy
applications that allow for REPL access via HTTP and HTTPS simplifies
configuration and can alleviate security concerns, and works around
limitations in various deployment environments where traditional
socket-based channels are limited or entirely unavailable.

### In a Ring web application

Once you have added Drawbridge to your project's dependencies, just
add its Ring handler to your application.  For example, if you're using
[Compojure](https://github.com/weavejester/compojure) for routing and
such:

```clojure
(require 'cemerick.drawbridge)

(let [nrepl-handler (cemerick.drawbridge/ring-handler)]
  (ANY "/repl" request (nrepl-handler request)))
```

With this, any HTTP or HTTPS client can send nREPL messages to the
`/repl` URI, and read responses from the same.  Conveniently, any
security measures applied within your application will work fine in
conjunction with Drawbridge; so, if you configure its route to require
authentication or authorization to some application-specific role, those
prerequisites will apply just as with any other Ring handler in the same
context.

Some things to be aware of when using `cemerick.drawbridge/ring-handler`:

 * It requires `GET` and `POST` requests
   to be routed to whatever URI to which it is mapped; other request
   methods result in an HTTP error response.
 * It requires these standard Ring middlewares to function properly:
   * `keyword-params`
   * `nested-params`
   * `wrap-params`

Especially if you are going to be connecting to your webapp's nREPL
endpoint with a client that uses Drawbridge's own HTTP/HTTPS client
transport (see below), this is all you need to know.

If you are interested in the implementation details and semantics,
perhaps because you'd like to implement support for Drawbridge in
non-Clojure nREPL clients, you'll want to review the documentation for
`ring-handler`, which contains additional important details.

### In Clojure tooling

Drawbridge also provides a client-side nREPL transport implementation
for the Ring handler in `cemerick.drawbridge.client/ring-client-transport`.

Note that the `cemerick.drawbridge.client` namespace implicitly adds
implementations to the `clojure.tools.nrepl/url-connect` multimethod for
`"http"` and `"https"` schemes. So, once this namespace is loaded, any
tool that uses `url-connect` will use `ring-client-transport` for
connecting to HTTP and HTTPS nREPL endpoints.

## TODO

The biggest outstanding issues are around the semantics of how HTTP
session (might optionally) map onto nREPL sessions.  Right now, they
don't at all, though HTTP sessions _are_ significant insofar as they
retain the message queue nREPL will dispatch responses to that are
emitted by asynchronous or long-running operations.

Secondarily, supporting nontextual REPL interactions over HTTP has not
yet been addressed at all.

## Need Help?

Send a message to the [clojure-tools](http://groups.google.com/group/clojure-tools)
mailing list, or ping `cemerick` on freenode irc or
[twitter](http://twitter.com/cemerick) if you have questions
or would like to contribute patches.

## License

Copyright © 2012 Chas Emerick and other contributors.

Distributed under the Eclipse Public License, the same as Clojure.
