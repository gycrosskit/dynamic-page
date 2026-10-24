Pod::Spec.new do |spec|
  spec.name = 'GycDynamicPageNative'
  spec.version = '1.0.0'
  spec.summary = 'Kuikly 动态页网络 Module 的 iOS 薄接收端'
  spec.homepage = 'https://github.com/gycrosskit'
  spec.license = { :type => 'Apache-2.0', :file => 'LICENSE' }
  spec.author = { 'GY CrossKit' => 'https://github.com/gycrosskit' }
  spec.source = { :git => 'https://github.com/gycrosskit/dynamic-page.git', :tag => spec.version.to_s }
  spec.source_files = 'ios/Sources/GycDynamicPageNative/**/*.swift'
  spec.dependency 'OpenKuiklyIOSRender', '2.29.1'
  spec.ios.deployment_target = '14.0'
  spec.swift_version = '5.9'
end
