import Foundation

enum SiteHTTP {
    static func configuration() -> URLSessionConfiguration {
        let config=URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest=30
        config.timeoutIntervalForResource=1800
        let expensive=UserDefaults.standard.bool(forKey:"allowExpensiveNetwork")
        config.allowsExpensiveNetworkAccess=expensive
        config.allowsConstrainedNetworkAccess=expensive
        return config
    }
    static func data(_ url: URL, limit: Int, headers: [String:String]=[:]) async throws -> (Data,HTTPURLResponse) {
        try await SiteDataRequest(limit:limit).run(url,headers:headers)
    }
}

// The delegate limits bytes while receiving, rather than after allocating a complete response.
private final class SiteDataRequest: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let limit: Int
    private var data=Data()
    private var response: HTTPURLResponse?
    private var failure: Error?
    private var session: URLSession?
    private var continuation: CheckedContinuation<(Data,HTTPURLResponse),Error>?
    init(limit: Int) { self.limit=limit }
    func run(_ url: URL, headers: [String:String]) async throws -> (Data,HTTPURLResponse) {
        try await withCheckedThrowingContinuation { c in
            continuation=c
            let queue=OperationQueue();queue.maxConcurrentOperationCount=1
            let s=URLSession(configuration:SiteHTTP.configuration(),delegate:self,delegateQueue:queue)
            session=s
            var request=URLRequest(url:url,cachePolicy:.reloadIgnoringLocalCacheData,timeoutInterval:30)
            for (key,value) in headers { request.setValue(value,forHTTPHeaderField:key) }
            s.dataTask(with:request).resume()
        }
    }
    func urlSession(_ session: URLSession,task: URLSessionTask,willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest,completionHandler: @escaping (URLRequest?)->Void) {
        failure=PlayerError.message("Сайт перенаправил запрос");completionHandler(nil)
    }
    func urlSession(_ session: URLSession,dataTask: URLSessionDataTask,didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition)->Void) {
        guard let r=response as? HTTPURLResponse, response.expectedContentLength<=Int64(limit) else {
            failure=PlayerError.message("Ответ сайта слишком большой");completionHandler(.cancel);return
        }
        self.response=r;completionHandler(.allow)
    }
    func urlSession(_ session: URLSession,dataTask: URLSessionDataTask,didReceive bytes: Data) {
        guard data.count<=limit-bytes.count else {
            failure=PlayerError.message("Ответ сайта слишком большой");dataTask.cancel();return
        }
        data.append(bytes)
    }
    func urlSession(_ session: URLSession,task: URLSessionTask,didCompleteWithError error: Error?) {
        let c=continuation;continuation=nil;self.session=nil
        session.finishTasksAndInvalidate()
        if let error=failure ?? error { c?.resume(throwing:error) }
        else if let response=response { c?.resume(returning:(data,response)) }
        else { c?.resume(throwing:PlayerError.message("Нет ответа сайта")) }
    }
}
